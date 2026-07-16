$udp = New-Object System.Net.Sockets.UdpClient
$udp.Client.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket, [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
$udp.ExclusiveAddressUse = $false
$udp.Client.Bind([System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 1900))
$udp.JoinMulticastGroup([System.Net.IPAddress]::Parse("239.255.255.250"))
$udp.Client.ReceiveTimeout = 15000
Write-Output "listening on 239.255.255.250:1900 for 15s..."
$start = Get-Date
$msearch = 0; $notify = 0; $resp = 0
while (((Get-Date) - $start).TotalSeconds -lt 15) {
  try {
    $re = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
    $data = $udp.Receive([ref]$re)
    $txt = [System.Text.Encoding]::ASCII.GetString($data)
    if ($txt -match "M-SEARCH") { $msearch++; Write-Output "M-SEARCH from $($re.Address):$($re.Port)" }
    elseif ($txt -match "NOTIFY") { $notify++; Write-Output "NOTIFY from $($re.Address):$($re.Port)" }
    elseif ($txt -match "HTTP/1.1 200") { $resp++; Write-Output "RESPONSE from $($re.Address):$($re.Port)" }
  } catch { }
}
Write-Output "done: M-SEARCH=$msearch NOTIFY=$notify RESPONSE=$resp"
$udp.Close()
