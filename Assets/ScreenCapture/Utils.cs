using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using UnityEngine;

namespace PupilLabs.ScreenCapture
{
    public static class NetworkUtils
    {
        public static IPAddress[] GetLocalIPAddresses()
        {
            List<IPAddress> addresses = new List<IPAddress>();
            Debug.Log("[NetworkUtils] getting local IP address");
            foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                Debug.Log($"[NetworkUtils] iterating over network interface of type: {ni.NetworkInterfaceType} and status {ni.OperationalStatus}");
                if (ni.OperationalStatus == OperationalStatus.Up)
                {
                    foreach (UnicastIPAddressInformation ip in ni.GetIPProperties().UnicastAddresses)
                    {
                        Debug.Log($"[NetworkUtils] iterating over unicast ip of address family: {ip.Address.AddressFamily}");
                        if (ip.Address.AddressFamily == AddressFamily.InterNetwork && IPAddress.IsLoopback(ip.Address) == false && ip.IsDnsEligible)
                        {
                            Debug.Log($"[NetworkUtils] adding local IP address: {ip.Address}");
                            addresses.Add(ip.Address);
                        }
                    }
                }
            }
            return addresses.ToArray();
        }

        public static byte[] NetworkBytesToLocal(byte[] networkBytes, int startIndex, int count)
        {
            byte[] localBytes = networkBytes.Skip(startIndex).Take(count).ToArray();
            if (BitConverter.IsLittleEndian)
            {
                Array.Reverse(localBytes);
            }
            return localBytes;
        }
    }

    public static class TimeUtils
    {
#if UNITY_EDITOR_WIN || UNITY_STANDALONE_WIN
        [DllImport("Kernel32.dll", CallingConvention = CallingConvention.Winapi)]
        private static extern void GetSystemTimePreciseAsFileTime(out long filetime);
#endif

#if UNITY_ANDROID && !UNITY_EDITOR
        private static readonly AndroidJavaClass androidSystem = new AndroidJavaClass("java.lang.System");
#endif

        /// <summary>
        /// Returns the Unix Epoch time in milliseconds (Time since Jan 1, 1970).
        /// </summary>
        public static long UnixTimeMs()
        {
#if UNITY_EDITOR_WIN || UNITY_STANDALONE_WIN
            // Windows execution path
            GetSystemTimePreciseAsFileTime(out long fileTime);
            return (fileTime - 116444736000000000L) / 10000L;

#elif UNITY_ANDROID
        // Quest / Android execution path
        return androidSystem.CallStatic<long>("currentTimeMillis");

#else
        // Fallback for Mac/Linux Editors
        return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
#endif
        }
    }
}
