$ep = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Parse("239.255.255.250"), 1900)
$udp = New-Object System.Net.Sockets.UdpClient
$udp.Client.ReceiveTimeout = 1500
$udp.Client.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket, [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
$msg = "M-SEARCH * HTTP/1.1`r`nHOST: 239.255.255.250:1900`r`nMAN: `"ssdp:discover`"`r`nMX: 2`r`nST: urn:schemas-upnp-org:device:MediaRenderer:1`r`n`r`n"
$bytes = [System.Text.Encoding]::ASCII.GetBytes($msg)
1..3 | ForEach-Object { [void]$udp.Send($bytes, $bytes.Length, $ep) }
Write-Output "M-SEARCH sent for MediaRenderer. Listening 5s..."
$deadline = (Get-Date).AddSeconds(5)
$count = 0
while ((Get-Date) -lt $deadline) {
  try {
    $re = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
    $data = $udp.Receive([ref]$re)
    $count++
    Write-Output "===== RESPONSE from $($re.Address):$($re.Port) ====="
    Write-Output ([System.Text.Encoding]::ASCII.GetString($data))
  } catch { }
}
Write-Output "Total responses: $count"
$udp.Close()
