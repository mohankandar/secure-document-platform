package com.securedoc.platform.ai.analysis;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Task 0064, 0110: a model server is asked only on this machine or a private network, never on the internet. */
public final class LocalModelServer {
    private LocalModelServer() {
    }

    /** Every address the host resolves to must be loopback, private or IPv6 unique-local. Asked each time. */
    public static boolean isLocal(String host) throws IOException {
        if (host == null || host.isBlank()) return false;
        try {
            for (InetAddress address : InetAddress.getAllByName(host.replace("[", "").replace("]", ""))) {
                boolean uniqueLocal = address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc;
                if (!address.isLoopbackAddress() && !address.isSiteLocalAddress() && !uniqueLocal) return false;
            }
            return true;
        } catch (UnknownHostException unknown) {
            throw new IOException("The model server's address is not known");
        }
    }
}
