using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net.Sockets;
using System.Threading;

namespace GorillaPassthrough
{
    /// <summary>The TCP socket to the Gorilla Passthrough mod inside the running Minecraft client.</summary>
    internal sealed class Link
    {
        private readonly ConcurrentQueue<Message> _inbox = new ConcurrentQueue<Message>();
        private readonly object _writeLock = new object();
        private TcpClient _client;
        private NetworkStream _stream;
        private volatile bool _open;
        private TcpClient _pending;
        private IAsyncResult _pendingResult;
        private int _pendingSince;

        public bool IsOpen => _open;

        /// <summary>
        /// Never waits: starts a connection attempt, and on later calls picks it up once it's done. (Waiting on the game's
        /// thread stalls every frame while Minecraft isn't running: on Windows a refused connection takes about 2 s.)
        /// </summary>
        public bool TryConnect()
        {
            try
            {
                if (_pending == null)
                {
                    _pending = new TcpClient();
                    _pendingResult = _pending.BeginConnect("127.0.0.1", Protocol.Port, null, null);
                    _pendingSince = Environment.TickCount;
                    return false;
                }
                if (!_pendingResult.IsCompleted)
                {
                    if (Environment.TickCount - _pendingSince > 5000)
                    {
                        DropPending();
                    }
                    return false;
                }
                TcpClient client = _pending;
                IAsyncResult result = _pendingResult;
                _pending = null;
                client.EndConnect(result);
                client.NoDelay = true;
                _client = client;
                _stream = client.GetStream();
                _open = true;
                new Thread(ReadLoop) { IsBackground = true, Name = "Gorilla Passthrough reader" }.Start();
                return true;
            }
            catch (Exception)
            {
                DropPending();
                return false;
            }
        }

        private void DropPending()
        {
            try { _pending?.Close(); } catch (Exception) { }
            _pending = null;
        }

        public bool TryReceive(out Message message) => _inbox.TryDequeue(out message);

        public void Send(byte type, JavaWriter payload)
        {
            if (!_open)
            {
                return;
            }
            byte[] body = payload != null ? payload.ToArray() : new byte[0];
            int length = body.Length + 1;
            var frame = new byte[4 + length];
            frame[0] = (byte)(length >> 24);
            frame[1] = (byte)(length >> 16);
            frame[2] = (byte)(length >> 8);
            frame[3] = (byte)length;
            frame[4] = type;
            Buffer.BlockCopy(body, 0, frame, 5, body.Length);
            try
            {
                lock (_writeLock)
                {
                    _stream.Write(frame, 0, frame.Length);
                }
            }
            catch (Exception e)
            {
                Plugin.Log.LogWarning("Gorilla Passthrough: lost Minecraft: " + e.Message);
                Close();
            }
        }

        public void Close()
        {
            _open = false;
            try { _client?.Close(); } catch (Exception) { }
        }

        private void ReadLoop()
        {
            var header = new byte[4];
            try
            {
                while (_open)
                {
                    ReadFully(header, 4);
                    int length = (header[0] << 24) | (header[1] << 16) | (header[2] << 8) | header[3];
                    if (length < 1 || length > (16 << 20))
                    {
                        throw new IOException("bad frame length " + length);
                    }
                    var frame = new byte[length];
                    ReadFully(frame, length);
                    var payload = new byte[length - 1];
                    Buffer.BlockCopy(frame, 1, payload, 0, length - 1);
                    _inbox.Enqueue(new Message { Type = frame[0], Payload = payload });
                }
            }
            catch (Exception e)
            {
                if (_open)
                {
                    Plugin.Log.LogWarning("Gorilla Passthrough: Minecraft connection closed: " + e.Message);
                }
                _open = false;
            }
        }

        private void ReadFully(byte[] buffer, int count)
        {
            int read = 0;
            while (read < count)
            {
                int n = _stream.Read(buffer, read, count - read);
                if (n <= 0)
                {
                    throw new IOException("connection closed");
                }
                read += n;
            }
        }
    }
}
