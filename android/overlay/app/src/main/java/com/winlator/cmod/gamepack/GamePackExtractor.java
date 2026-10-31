package com.winlator.cmod.gamepack;

import com.winlator.cmod.core.FileUtils;

import net.sf.sevenzipjbinding.ExtractAskMode;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IArchiveExtractCallback;
import net.sf.sevenzipjbinding.IArchiveOpenCallback;
import net.sf.sevenzipjbinding.IArchiveOpenVolumeCallback;
import net.sf.sevenzipjbinding.ICryptoGetTextPassword;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.IInStream;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extracts RAR (RAR4/RAR5, multi-volume), 7z and zip archives through 7-Zip-JBinding,
 * and .tar.zst / .tar.xz archives (optionally split into parts) through commons-compress.
 */
public class GamePackExtractor {
    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    private final AtomicBoolean cancelled;

    public GamePackExtractor(AtomicBoolean cancelled) {
        this.cancelled = cancelled;
    }

    public void extract(List<File> parts, String password, File destination, ProgressListener listener) throws IOException {
        if (parts.isEmpty()) throw new IOException("No archive parts");
        destination.mkdirs();
        String name = parts.get(0).getName().toLowerCase(Locale.ENGLISH);
        if (name.contains(".tar.zst") || name.contains(".tzst")) {
            extractTar(parts, true, destination, listener);
        }
        else if (name.contains(".tar.xz") || name.contains(".txz")) {
            extractTar(parts, false, destination, listener);
        }
        else {
            extractWith7Zip(parts, password, destination, listener);
        }
    }

    private void extractWith7Zip(List<File> parts, String password, File destination, ProgressListener listener) throws IOException {
        VolumeCallback volumeCallback = new VolumeCallback(parts, password);
        IInArchive archive = null;
        try {
            IInStream firstStream = volumeCallback.getStream(parts.get(0).getAbsolutePath());
            if (firstStream == null) throw new FileNotFoundException(parts.get(0).getPath());
            archive = SevenZip.openInArchive(null, firstStream, volumeCallback);

            ExtractCallback extractCallback = new ExtractCallback(archive, destination, password, listener);
            archive.extract(null, false, extractCallback);
            extractCallback.closeCurrent();
            if (extractCallback.error != null) throw extractCallback.error;
        }
        catch (SevenZipException e) {
            throw new IOException(cancelled.get() ? "Cancelled" : "Archive error: " + e.getMessage(), e);
        }
        finally {
            if (archive != null) {
                try {
                    archive.close();
                }
                catch (SevenZipException ignored) {}
            }
            volumeCallback.close();
        }
    }

    private void extractTar(List<File> parts, boolean zstd, File destination, ProgressListener listener) throws IOException {
        long total = 0;
        List<InputStream> streams = new ArrayList<>();
        for (File part : parts) {
            total += part.length();
            streams.add(new FileInputStream(part));
        }
        final long totalBytes = total;
        CountingInputStream counting = new CountingInputStream(new SequenceInputStream(Collections.enumeration(streams)));
        InputStream buffered = new BufferedInputStream(counting, 1 << 20);
        String root = destination.getCanonicalPath() + File.separator;

        try (InputStream decompressed = zstd ? new ZstdCompressorInputStream(buffered) : new XZCompressorInputStream(buffered);
             TarArchiveInputStream tar = new TarArchiveInputStream(decompressed)) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[1 << 16];
            long lastReport = 0;
            while ((entry = tar.getNextTarEntry()) != null) {
                checkCancelled();
                File file = safeResolve(destination, root, entry.getName());
                if (entry.isDirectory()) {
                    file.mkdirs();
                }
                else if (entry.isSymbolicLink()) {
                    file.getParentFile().mkdirs();
                    FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                }
                else {
                    file.getParentFile().mkdirs();
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
                        int read;
                        while ((read = tar.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            long now = System.currentTimeMillis();
                            if (listener != null && now - lastReport > 250) {
                                listener.onProgress(counting.count, totalBytes);
                                lastReport = now;
                            }
                        }
                    }
                }
            }
            if (listener != null) listener.onProgress(totalBytes, totalBytes);
        }
    }

    /** Resolves an archive entry path inside the destination, rejecting "../" escapes. */
    private static File safeResolve(File destination, String canonicalRoot, String entryName) throws IOException {
        File file = new File(destination, entryName.replace('\\', '/'));
        String path = file.getCanonicalPath();
        if (!path.startsWith(canonicalRoot) && !(path + File.separator).equals(canonicalRoot)) {
            throw new IOException("Blocked unsafe archive entry: " + entryName);
        }
        return file;
    }

    private void checkCancelled() throws IOException {
        if (cancelled.get()) throw new IOException("Cancelled");
    }

    private static class CountingInputStream extends FilterInputStream {
        volatile long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) count += read;
            return read;
        }
    }

    /** Opens archive volumes on demand (part1.rar, part2.rar ... or .r00, .r01 ...). */
    private static class VolumeCallback implements IArchiveOpenVolumeCallback, IArchiveOpenCallback, ICryptoGetTextPassword, Closeable {
        private final Map<String, RandomAccessFile> openFiles = new HashMap<>();
        private final File directory;
        private final String password;
        private String currentName;

        VolumeCallback(List<File> parts, String password) {
            this.directory = parts.get(0).getParentFile();
            this.password = password;
        }

        @Override
        public Object getProperty(PropID propID) {
            return propID == PropID.NAME ? currentName : null;
        }

        @Override
        public IInStream getStream(String filename) throws SevenZipException {
            File file = new File(filename);
            if (!file.isAbsolute()) file = new File(directory, filename);
            if (!file.isFile()) return null;
            try {
                String key = file.getAbsolutePath();
                RandomAccessFile raf = openFiles.get(key);
                if (raf == null) {
                    raf = new RandomAccessFile(file, "r");
                    openFiles.put(key, raf);
                }
                raf.seek(0);
                currentName = key;
                return new RandomAccessFileInStream(raf);
            }
            catch (IOException e) {
                throw new SevenZipException("Unable to open volume " + filename, e);
            }
        }

        @Override
        public void setTotal(Long files, Long bytes) {}

        @Override
        public void setCompleted(Long files, Long bytes) {}

        @Override
        public String cryptoGetTextPassword() {
            return password;
        }

        @Override
        public void close() {
            for (RandomAccessFile raf : openFiles.values()) {
                try {
                    raf.close();
                }
                catch (IOException ignored) {}
            }
            openFiles.clear();
        }
    }

    private class ExtractCallback implements IArchiveExtractCallback, ICryptoGetTextPassword {
        private final IInArchive archive;
        private final File destination;
        private final String canonicalRoot;
        private final String password;
        private final ProgressListener listener;
        private long total;
        private long lastReport;
        private OutputStream current;
        IOException error;

        ExtractCallback(IInArchive archive, File destination, String password, ProgressListener listener) throws IOException {
            this.archive = archive;
            this.destination = destination;
            this.canonicalRoot = destination.getCanonicalPath() + File.separator;
            this.password = password;
            this.listener = listener;
        }

        @Override
        public ISequentialOutStream getStream(int index, ExtractAskMode extractAskMode) throws SevenZipException {
            closeCurrent();
            if (cancelled.get()) throw new SevenZipException("Cancelled");
            if (extractAskMode != ExtractAskMode.EXTRACT) return null;

            String path = (String)archive.getProperty(index, PropID.PATH);
            boolean isFolder = Boolean.TRUE.equals(archive.getProperty(index, PropID.IS_FOLDER));
            if (path == null || path.isEmpty()) return null;

            try {
                File file = safeResolve(destination, canonicalRoot, path);
                if (isFolder) {
                    file.mkdirs();
                    return null;
                }
                file.getParentFile().mkdirs();
                current = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
            }
            catch (IOException e) {
                error = e;
                throw new SevenZipException(e.getMessage(), e);
            }

            final OutputStream out = current;
            return data -> {
                try {
                    out.write(data);
                }
                catch (IOException e) {
                    error = e;
                    throw new SevenZipException("Write failed (disk full?)", e);
                }
                return data.length;
            };
        }

        void closeCurrent() {
            if (current != null) {
                try {
                    current.close();
                }
                catch (IOException e) {
                    if (error == null) error = e;
                }
                current = null;
            }
        }

        @Override
        public void prepareOperation(ExtractAskMode extractAskMode) {}

        @Override
        public void setOperationResult(ExtractOperationResult result) throws SevenZipException {
            closeCurrent();
            if (result != ExtractOperationResult.OK) {
                String message = result == ExtractOperationResult.WRONG_PASSWORD || (result == ExtractOperationResult.DATAERROR && !password.isEmpty())
                        ? "Wrong archive password" : "Archive is damaged: " + result;
                error = new IOException(message);
                throw new SevenZipException(message);
            }
        }

        @Override
        public void setTotal(long total) {
            this.total = total;
        }

        @Override
        public void setCompleted(long complete) {
            long now = System.currentTimeMillis();
            if (listener != null && (now - lastReport > 250 || complete == total)) {
                listener.onProgress(complete, total);
                lastReport = now;
            }
        }

        @Override
        public String cryptoGetTextPassword() {
            return password;
        }
    }
}
