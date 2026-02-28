using PupilLabs.ScreenCapture;
using System.Linq;
using TMPro;
using UnityEngine;

public class Handlers : MonoBehaviour
{
    public TextMeshProUGUI field;

    public void OnCaptureStarted()
    {
        var ips = NetworkUtils.GetLocalIPAddresses();
        string ipLog = string.Join("\n", ips.Select(ip => ip.ToString()));
        field.text = ipLog;
    }
}
