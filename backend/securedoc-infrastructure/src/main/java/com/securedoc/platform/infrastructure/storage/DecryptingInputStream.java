package com.securedoc.platform.infrastructure.storage;

import com.documentplatform.crypto.CryptoException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Task 0060: reads what a writer produces on a thread of its own, at most a few chunks ahead, so a large document is never
 * held in memory or written anywhere plain. A failure of the writer is the reader's failure, never an early end.
 */
final class DecryptingInputStream extends InputStream {
    interface Writer {
        void write(OutputStream out) throws IOException;
    }

    private static final int CHUNKS_AHEAD = 4;
    /** A reader that neither reads nor closes for this long is given up. */
    private static final long READER_WAIT_MINUTES = 30;

    private record Piece(byte[] bytes, Exception failure) {
        static final Piece END = new Piece(null, null);
    }

    private final BlockingQueue<Piece> pieces = new ArrayBlockingQueue<>(CHUNKS_AHEAD);
    private Thread writer;
    private Piece current;
    private int position;
    private boolean closed;

    private DecryptingInputStream() {
    }

    /** Starts the writer and waits for its first bytes, so a writer that can't start fails here. */
    static InputStream open(Writer source) throws IOException {
        DecryptingInputStream stream = new DecryptingInputStream();
        stream.writer = Thread.ofVirtual().name("stored-file-reader").start(() -> stream.run(source));
        Piece first;
        try {
            first = stream.pieces.take();
        } catch (InterruptedException e) {
            stream.close();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while a stored file was opened");
        }
        if (first.failure() instanceof CryptoException refused) throw refused;
        if (first.failure() instanceof IOException io) throw io;
        if (first.failure() != null) throw new IOException("Stored file could not be opened", first.failure());
        stream.current = first;
        return stream;
    }

    private void run(Writer source) {
        Piece last = Piece.END;
        try {
            source.write(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (len > 0) put(new Piece(Arrays.copyOfRange(b, off, off + len), null));
                }
            });
        } catch (Exception failed) {
            last = new Piece(null, failed);
        } catch (Throwable failed) {
            last = new Piece(null, new IOException("Stored file could not be read", failed));
        }
        try {
            put(last);
        } catch (IOException nobodyReads) {
            // Closed meanwhile.
        }
    }

    private void put(Piece piece) throws IOException {
        try {
            if (!pieces.offer(piece, READER_WAIT_MINUTES, TimeUnit.MINUTES)) {
                throw new IOException("Nobody reads the stored file");
            }
        } catch (InterruptedException closedMeanwhile) {
            throw new InterruptedIOException("The stored file was closed");
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        java.util.Objects.checkFromIndexSize(off, len, b.length);
        if (closed) throw new IOException("Stream closed");
        if (len == 0) return 0;
        while (current.bytes() != null && position == current.bytes().length) next();
        if (current.failure() != null) throw new IOException("Stored file could not be read", current.failure());
        if (current.bytes() == null) return -1;
        int count = Math.min(len, current.bytes().length - position);
        System.arraycopy(current.bytes(), position, b, off, count);
        position += count;
        return count;
    }

    @Override
    public int available() {
        return closed || current.bytes() == null ? 0 : current.bytes().length - position;
    }

    private void next() throws IOException {
        try {
            current = pieces.take();
            position = 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while a stored file was read");
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        writer.interrupt();
        pieces.clear();
    }
}
