package de.danoeh.antennapod.asr;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ModelDownloaderTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ServerSocket serverSocket;
    private volatile byte[] responseBody;

    @Before
    public void startServer() throws IOException {
        serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(this::serveOnce);
        thread.setDaemon(true);
        thread.start();
    }

    @After
    public void stopServer() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    private void serveOnce() {
        try (Socket socket = serverSocket.accept()) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // drain the request headers
            }
            byte[] body = responseBody == null ? new byte[0] : responseBody;
            OutputStream output = socket.getOutputStream();
            String header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            output.write(header.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        } catch (IOException e) {
            // the test closed the socket or the request failed
        }
    }

    @Test
    public void downloadsAndInstallsAllFiles() throws IOException {
        File root = folder.newFolder("models");
        AsrModelStore store = new AsrModelStore(root);
        AsrModelDescriptor descriptor = AsrModelCatalog.forLanguage(AsrModelLanguage.ZH);
        byte[] body = archive(descriptor, Collections.emptySet());
        AtomicLong lastRead = new AtomicLong();
        AtomicLong reportedTotal = new AtomicLong();

        new ModelDownloader(store, 2000, 5000).download(descriptor, serve(body),
                (read, total) -> {
                    lastRead.set(read);
                    reportedTotal.set(total);
                });

        assertTrue(store.isInstalled(AsrModelLanguage.ZH));
        assertTrue(lastRead.get() > 0);
        assertEquals(body.length, reportedTotal.get());
        for (String name : descriptor.getExpectedFiles()) {
            assertEquals("content-" + name,
                    read(new File(store.modelDir(AsrModelLanguage.ZH), name)));
        }
    }

    @Test
    public void missingFileIsRejected() throws IOException {
        File root = folder.newFolder("models");
        AsrModelStore store = new AsrModelStore(root);
        AsrModelDescriptor descriptor = AsrModelCatalog.forLanguage(AsrModelLanguage.ZH);
        byte[] body = archive(descriptor, Collections.singleton(descriptor.getJoinerFile()));

        assertThrows(IOException.class,
                () -> new ModelDownloader(store, 2000, 5000).download(descriptor, serve(body), null));

        assertFalse(store.isInstalled(AsrModelLanguage.ZH));
        assertFalse(new File(root, descriptor.getId()).exists());
    }

    @Test
    public void checksumMismatchIsRejected() throws IOException {
        File root = folder.newFolder("models");
        AsrModelStore store = new AsrModelStore(root);
        AsrModelDescriptor catalog = AsrModelCatalog.forLanguage(AsrModelLanguage.ZH);
        AsrModelDescriptor descriptor = new AsrModelDescriptor(
                catalog.getLanguage(), catalog.getId(), catalog.getDownloadUrl(),
                catalog.getDownloadSizeBytes(),
                catalog.getEncoderFile(), catalog.getDecoderFile(), catalog.getJoinerFile(),
                catalog.getTokensFile(),
                Collections.singletonMap(catalog.getEncoderFile(), "deadbeef"));
        byte[] body = archive(catalog, Collections.emptySet());

        assertThrows(IOException.class,
                () -> new ModelDownloader(store, 2000, 5000).download(descriptor, serve(body), null));

        assertFalse(store.isInstalled(AsrModelLanguage.ZH));
    }

    @Test
    public void corruptArchiveIsRejected() throws IOException {
        File root = folder.newFolder("models");
        AsrModelStore store = new AsrModelStore(root);
        AsrModelDescriptor descriptor = AsrModelCatalog.forLanguage(AsrModelLanguage.ZH);
        byte[] body = "not a tar.bz2 archive".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> new ModelDownloader(store, 2000, 5000).download(descriptor, serve(body), null));

        assertFalse(store.isInstalled(AsrModelLanguage.ZH));
    }

    @Test
    public void installedModelSkipsTheNetwork() throws IOException {
        File root = folder.newFolder("models");
        AsrModelStore store = new AsrModelStore(root);
        AsrModelDescriptor descriptor = AsrModelCatalog.forLanguage(AsrModelLanguage.ZH);
        File modelDir = store.modelDir(AsrModelLanguage.ZH);
        assertTrue(modelDir.mkdirs());
        for (String name : descriptor.getExpectedFiles()) {
            write(new File(modelDir, name), "installed");
        }
        AtomicBoolean progress = new AtomicBoolean();

        new ModelDownloader(store, 2000, 5000)
                .download(descriptor, "http://127.0.0.1:1/never", (read, total) -> progress.set(true));

        assertTrue(store.isInstalled(AsrModelLanguage.ZH));
        assertFalse(progress.get());
    }

    private String serve(byte[] body) {
        responseBody = body;
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/model.tar.bz2";
    }

    private static byte[] archive(AsrModelDescriptor descriptor, Set<String> omitted)
            throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : descriptor.getExpectedFiles()) {
            if (!omitted.contains(name)) {
                entries.put(descriptor.getId() + "/" + name,
                        ("content-" + name).getBytes(StandardCharsets.UTF_8));
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bzip = new BZip2CompressorOutputStream(bytes);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(bzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                TarArchiveEntry tarEntry = new TarArchiveEntry(entry.getKey());
                tarEntry.setSize(entry.getValue().length);
                tar.putArchiveEntry(tarEntry);
                tar.write(entry.getValue());
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
        return bytes.toByteArray();
    }

    private static String read(File file) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void write(File file, String content) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
