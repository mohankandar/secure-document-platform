#requires -Version 5.1
<#
.SYNOPSIS
    Task 0015 test tool: a mock Ollama whose /api/chat answer contains the fake test values.

.DESCRIPTION
    Same shape as Start-Mock-Ollama-Node.ps1 (TcpListener, no admin rights, no dependency), but
    the chat answer is a findings list naming the fake SSN, card number and date of birth, so the
    AI path carries the values through the application exactly as a real model answer would.
    Listens on 127.0.0.1 only. Stop it by ending the process.
#>
param(
    [int]$Port = 11499,
    [string]$Model = "qwen3.5:9b",
    # Task 0048: how long each chat answer takes (the two-server check keeps Find jobs running long enough to stop a
    # server in the middle of one). 0, the default, answers at once, as before.
    [int]$DelayMs = 0
)

$ErrorActionPreference = "Stop"

# The answer content (a JSON string inside the Ollama envelope). contextText is empty, so the
# application locates each value by its unique place on the page.
$answer = '{"findings":[' +
    '{"pageNumber":1,"entityType":"SSN","matchedText":"412-55-7531","contextText":"","confidence":0.97},' +
    '{"pageNumber":1,"entityType":"CREDIT_CARD_NUMBER","matchedText":"4000 0566 5566 5556","contextText":"","confidence":0.97},' +
    '{"pageNumber":1,"entityType":"DATE_OF_BIRTH","matchedText":"03/14/1962","contextText":"","confidence":0.97}' +
    ']}'
$escapedAnswer = $answer.Replace('\', '\\').Replace('"', '\"')
$chatBody = '{"message":{"content":"' + $escapedAnswer + '"},"done_reason":"stop"}'

$source = @'
using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

public sealed class LexaVaultFakeValueOllama
{
    private readonly int port;
    private readonly string model;
    private readonly string chatBody;
    public int DelayMs;
    private TcpListener listener;

    public LexaVaultFakeValueOllama(int port, string model, string chatBody)
    {
        this.port = port; this.model = model; this.chatBody = chatBody;
    }

    public void Start()
    {
        listener = new TcpListener(IPAddress.Loopback, port);
        listener.Start();
        Thread t = new Thread(AcceptLoop);
        t.IsBackground = true;
        t.Start();
    }

    private void AcceptLoop()
    {
        while (true)
        {
            TcpClient client = listener.AcceptTcpClient();
            ThreadPool.QueueUserWorkItem(Handle, client);
        }
    }

    private void Handle(object state)
    {
        TcpClient client = (TcpClient)state;
        try
        {
            using (client)
            using (NetworkStream stream = client.GetStream())
            {
                string path = ReadRequestPath(stream);
                if (path == "/api/tags")
                {
                    Send(stream, 200, "{\"models\":[{\"name\":\"" + model + "\",\"model\":\"" + model + "\"}]}");
                }
                else if (path == "/api/chat")
                {
                    if (DelayMs > 0) Thread.Sleep(DelayMs);
                    Send(stream, 200, chatBody);
                }
                else
                {
                    Send(stream, 404, "{}");
                }
            }
        }
        catch { try { client.Close(); } catch { } }
    }

    private static string ReadRequestPath(NetworkStream stream)
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
                int.TryParse(line.Substring(15).Trim(), out contentLength);
        }
        char[] buffer = new char[Math.Max(0, contentLength)];
        int offset = 0;
        while (offset < contentLength)
        {
            int read = reader.Read(buffer, offset, contentLength - offset);
            if (read <= 0) break;
            offset += read;
        }
        string[] parts = requestLine.Split(' ');
        return parts.Length < 2 ? null : parts[1];
    }

    private static void Send(NetworkStream stream, int status, string body)
    {
        byte[] bodyBytes = Encoding.UTF8.GetBytes(body);
        string headers = "HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: "
            + bodyBytes.Length + "\r\nConnection: close\r\n\r\n";
        byte[] headerBytes = Encoding.ASCII.GetBytes(headers);
        stream.Write(headerBytes, 0, headerBytes.Length);
        stream.Write(bodyBytes, 0, bodyBytes.Length);
        stream.Flush();
    }
}
'@

Add-Type -TypeDefinition $source -Language CSharp
$server = New-Object LexaVaultFakeValueOllama($Port, $Model, $chatBody)
$server.DelayMs = $DelayMs
$server.Start()
Write-Host "Fake-value mock Ollama listening on http://127.0.0.1:$Port (model $Model)"
while ($true) { Start-Sleep -Seconds 1 }
