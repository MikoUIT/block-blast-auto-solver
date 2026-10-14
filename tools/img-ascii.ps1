Add-Type -AssemblyName System.Drawing
# Renders a JPEG/PNG as ASCII art (luminance ramp), so the board/grid can be inspected as text
# when image viewing is unavailable. Usage:  powershell -File tools\img-ascii.ps1 <path> [cols] [rows]
$path = $args[0]
$cols = if ($args.Count -gt 1) { [int]$args[1] } else { 78 }
$rows = if ($args.Count -gt 2) { [int]$args[2] } else { 60 }
$bmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $path).Path)
$W = $bmp.Width; $H = $bmp.Height
$ramp = ' .:-=+*#%@'
$sb = New-Object System.Text.StringBuilder
for ($ry = 0; $ry -lt $rows; $ry++) {
  for ($rx = 0; $rx -lt $cols; $rx++) {
    $x = [int]($rx * $W / $cols); $y = [int]($ry * $H / $rows)
    $c = $bmp.GetPixel($x, $y)
    $l = ($c.R * 0.299 + $c.G * 0.587 + $c.B * 0.114)
    $idx = [int]($l / 256 * $ramp.Length); if ($idx -ge $ramp.Length) { $idx = $ramp.Length - 1 }
    [void]$sb.Append($ramp[$idx])
  }
  [void]$sb.Append("`n")
}
Write-Output $sb.ToString()
$bmp.Dispose()
