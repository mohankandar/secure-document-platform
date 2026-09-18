param(
    [int]$Port = 11435,
    [string]$Model = "qwen3.5:4b",
    [ValidateSet("SUCCESS", "HTTP503", "DELAY", "WRONG_MODEL")]
    [string]$Mode = "SUCCESS",
    [int]$DelaySeconds = 15
)

$ErrorActionPreference = "Stop"

# R3: the mock accepts each connection on an independent worker so /api/tags remains
# responsive while a DELAY /api/chat request is in flight. This makes saturation tests
# model independent inference capacity instead of a single-threaded test-server artifact.
$source = @'
using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

public sealed class LexaVaultMockOllamaServer
{
    private readonly int port;
    private readonly string model;
    private readonly string mode;
    private readonly int delaySeconds;
    private TcpListener listener;
    private Thread acceptThread;
    private volatile bool stopping;

    public LexaVaultMockOllamaServer(int port, string model, string mode, int delaySeconds)
    {
        this.port = port;
        this.model = model;
        this.mode = mode;
        this.delaySeconds = Math.Max(1, delaySeconds);
    }

    public void Start()
    {
        listener = new TcpListener(IPAddress.Loopback, port);
        listener.Start();
        acceptThread = new Thread(AcceptLoop);
        acceptThread.IsBackground = true;
        acceptThread.Name = "LexaVault-Mock-Ollama-Accept";
        acceptThread.Start();
    }

    public void Stop()
    {
        stopping = true;
        try { if (listener != null) listener.Stop(); } catch { }
        try { if (acceptThread != null && acceptThread.IsAlive) acceptThread.Join(1000); } catch { }
    }

    private void AcceptLoop()
    {
        while (!stopping)
        {
            TcpClient client = null;
            try
            {
                client = listener.AcceptTcpClient();
                ThreadPool.QueueUserWorkItem(HandleClient, client);
            }
            catch (SocketException)
            {
                if (!stopping) throw;
            }
            catch (ObjectDisposedException)
            {
                if (!stopping) throw;
            }
        }
    }

    private void HandleClient(object state)
    {
        TcpClient client = (TcpClient)state;
        try
        {
            using (client)
            using (NetworkStream stream = client.GetStream())
            {
                Request request = ReadRequest(stream);
                if (request == null)
                {
                    Send(stream, 400, "Bad Request", "{}");
                    return;
                }

                if (request.Path == "/api/tags")
                {
                    string reportedModel = mode == "WRONG_MODEL" ? "lexavault-wrong-model" : model;
                    string body = "{\"models\":[{\"name\":\"" + JsonEscape(reportedModel) + "\",\"model\":\"" + JsonEscape(reportedModel) + "\"}]}";
                    Send(stream, 200, "OK", body);
                    return;
                }

                if (request.Path == "/api/chat")
                {
                    if (mode == "HTTP503")
                    {
                        Send(stream, 503, "Service Unavailable", "{}");
                        return;
                    }
                    if (mode == "WRONG_MODEL")
                    {
                        Send(stream, 500, "Unexpected Chat", "{}");
                        return;
                    }
                    if (mode == "DELAY")
                    {
                        Thread.Sleep(TimeSpan.FromSeconds(delaySeconds));
                    }
                    Send(stream, 200, "OK", "{\"message\":{\"content\":\"{\\\"findings\\\":[]}\"},\"done_reason\":\"stop\"}");
                    return;
                }

                Send(stream, 404, "Not Found", "{}");
            }
        }
        catch
        {
            try { client.Close(); } catch { }
        }
    }

    private static Request ReadRequest(NetworkStream stream)
    {
        StreamReader reader = new StreamReader(stream, Encoding.ASCII, false, 4096, true);
        string requestLine = reader.ReadLine();
        if (String.IsNullOrWhiteSpace(requestLine)) return null;

        int contentLength = 0;
        while (true)
        {
            string line = reader.ReadLine();
            if (line == null || line.Length == 0) break;
            if (line.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase))
            {
                int.TryParse(line.Substring("Content-Length:".Length).Trim(), out contentLength);
            }
        }

        if (contentLength > 0)
        {
            char[] buffer = new char[contentLength];
            int offset = 0;
            while (offset < contentLength)
            {
                int read = reader.Read(buffer, offset, contentLength - offset);
                if (read <= 0) break;
                offset += read;
            }
        }

        string[] parts = requestLine.Split(' ');
        if (parts.Length < 2) return null;
        return new Request(parts[0], parts[1]);
    }

    private static void Send(NetworkStream stream, int statusCode, string statusText, string body)
    {
        byte[] bodyBytes = Encoding.UTF8.GetBytes(body);
        string headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                         "Content-Type: application/json\r\n" +
                         "Content-Length: " + bodyBytes.Length + "\r\n" +
                         "Connection: close\r\n\r\n";
        byte[] headerBytes = Encoding.ASCII.GetBytes(headers);
        stream.Write(headerBytes, 0, headerBytes.Length);
        stream.Write(bodyBytes, 0, bodyBytes.Length);
        stream.Flush();
    }

    private static string JsonEscape(string value)
    {
        return (value ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"");
    }

    private sealed class Request
    {
        public readonly string Method;
        public readonly string Path;
        public Request(string method, string path) { Method = method; Path = path; }
    }
}
'@

Add-Type -TypeDefinition $source -Language CSharp
$server = New-Object LexaVaultMockOllamaServer($Port, $Model, $Mode, $DelaySeconds)
$server.Start()

Write-Host "LexaVault mock Ollama node listening on http://127.0.0.1:$Port"
Write-Host "Mode=$Mode Model=$Model. Concurrent health probes enabled. Press Ctrl+C to stop."

try {
    while ($true) { Start-Sleep -Seconds 1 }
}
finally {
    $server.Stop()
}
