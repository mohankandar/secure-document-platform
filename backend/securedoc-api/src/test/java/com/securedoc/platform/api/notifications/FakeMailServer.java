package com.securedoc.platform.api.notifications;

import javax.net.ssl.SSLServerSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Task 0059: a mail server on this machine that takes every message and sends none on. Tests only. */
public final class FakeMailServer implements AutoCloseable {

    /** One message: who it was for, and the whole text. */
    public record Mail(List<String> to, String text) {
        /** The links of a line such as "Approve: http://...". */
        public String link(String label) {
            String unfolded = text.replace("=\r\n", "").replace("=3D", "=");
            for (String line : unfolded.split("\r?\n")) {
                if (line.startsWith(label) && line.contains("http")) return line.substring(line.indexOf("http")).strip();
            }
            int at = unfolded.indexOf(label);
            return at < 0 ? null : unfolded.substring(unfolded.indexOf("http", at)).split("\\s")[0];
        }
    }

    private final ServerSocket socket;
    private final List<Mail> mails = new CopyOnWriteArrayList<>();
    private final List<String> logins = new CopyOnWriteArrayList<>();
    private volatile boolean open = true;

    /** @param tls null for a plain server */
    public FakeMailServer(SSLServerSocketFactory tls) throws IOException {
        socket = tls == null ? new ServerSocket(0, 50, InetAddress.getLoopbackAddress())
                : tls.createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::accept, "fake-mail");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    public List<Mail> mails() {
        return mails;
    }

    /** What was given to AUTH, decoded. */
    public List<String> logins() {
        return logins;
    }

    /** Waits until this many messages arrived, up to 30 seconds. */
    public List<Mail> await(int count) throws InterruptedException {
        long until = System.currentTimeMillis() + 30_000;
        while (mails.size() < count && System.currentTimeMillis() < until) Thread.sleep(100);
        return mails;
    }

    private void accept() {
        while (open) {
            try {
                Socket s = socket.accept();
                Thread t = new Thread(() -> talk(s), "fake-mail-talk");
                t.setDaemon(true);
                t.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void talk(Socket s) {
        try (s; BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {
            say(out, "220 fake");
            List<String> to = new CopyOnWriteArrayList<>();
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                String upper = line.toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("EHLO")) say(out, "250-fake\r\n250 AUTH LOGIN PLAIN XOAUTH2");
                else if (upper.startsWith("AUTH XOAUTH2 ") || upper.startsWith("AUTH PLAIN ")) {
                    logins.add(decode(line.substring(line.lastIndexOf(' ') + 1)));
                    say(out, "235 ok");
                } else if (upper.startsWith("AUTH LOGIN")) {
                    String user;
                    if (line.length() > 11) {
                        user = decode(line.substring(11).strip());
                    } else {
                        say(out, "334 VXNlcm5hbWU6");
                        user = decode(in.readLine());
                    }
                    say(out, "334 UGFzc3dvcmQ6");
                    logins.add(user + "\u0000" + decode(in.readLine()));
                    say(out, "235 ok");
                } else if (upper.startsWith("RCPT TO:")) {
                    to.add(line.substring(8).replaceAll("[<> ]", "").strip());
                    say(out, "250 ok");
                } else if (upper.equals("DATA")) {
                    say(out, "354 go");
                    StringBuilder text = new StringBuilder();
                    for (String l = in.readLine(); l != null && !l.equals("."); l = in.readLine()) text.append(l).append("\r\n");
                    mails.add(new Mail(List.copyOf(to), text.toString()));
                    to.clear();
                    say(out, "250 ok");
                } else if (upper.equals("QUIT")) {
                    say(out, "221 bye");
                    return;
                } else say(out, "250 ok");
            }
        } catch (IOException gone) {
            // the sender hung up
        }
    }

    private static String decode(String base64) {
        return new String(Base64.getDecoder().decode(base64.strip()), StandardCharsets.UTF_8);
    }

    private static void say(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        open = false;
        socket.close();
    }
}
