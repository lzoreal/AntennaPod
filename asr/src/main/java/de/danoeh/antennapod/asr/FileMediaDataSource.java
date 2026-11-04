package de.danoeh.antennapod.asr;

import android.media.MediaDataSource;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Random-access view of a local file for MediaExtractor. */
final class FileMediaDataSource extends MediaDataSource {
    private final RandomAccessFile file;
    private final long size;
    private final Object lock = new Object();

    FileMediaDataSource(@NonNull File file) throws IOException {
        this.file = new RandomAccessFile(file, "r");
        this.size = this.file.length();
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (position < 0 || offset < 0 || size < 0) {
            throw new IOException("invalid read window");
        }
        if (position >= this.size) {
            return -1;
        }
        int toRead = (int) Math.min(size, this.size - position);
        synchronized (lock) {
            file.seek(position);
            return file.read(buffer, offset, toRead);
        }
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            file.close();
        }
    }
}
