Add-Type -AssemblyName System.Drawing
# Offline port of the autocorrelation-based GridDetector. Prints the detected board geometry so the
# algorithm can be validated against real captured frames before rebuilding the APK.
# Usage: powershell -File tools\grid-detect.ps1 <colour.jpg>

$path = $args[0]
$bmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $path).Path)
$W = $bmp.Width; $H = $bmp.Height
$px = New-Object 'int[]' ($W * $H)
for ($y = 0; $y -lt $H; $y++) { for ($x = 0; $x -lt $W; $x++) {
  $c = $bmp.GetPixel($x, $y); $px[$y*$W+$x] = [int](($c.R*77 + $c.G*151 + $c.B*28) -shr 8) } }
$bmp.Dispose()

$colE = New-Object 'int[]' $W
$rowE = New-Object 'int[]' $H
for ($y = 1; $y -lt $H; $y++) { $b=$y*$W; $p=($y-1)*$W; for ($x = 1; $x -lt $W; $x++) {
  $g = [Math]::Abs($px[$b+$x]-$px[$b+$x-1]) + [Math]::Abs($px[$b+$x]-$px[$p+$x]); $colE[$x]+=$g; $rowE[$y]+=$g } }

function Find-Pitch($e, $minP, $maxP) {
  $mean = ($e | Measure-Object -Average).Average
  $c = New-Object 'double[]' $e.Count
  for ($i=0;$i -lt $e.Count;$i++){ $c[$i] = $e[$i] - $mean }
  $bestP=-1; $best=0.0
  $hi = [Math]::Min([int]($e.Count/8), $maxP)
  for ($p=$minP; $p -le $hi; $p++) {
    $sum=0.0; $n=0; for ($i=0;$i+$p -lt $e.Count;$i++){ $sum += $c[$i]*$c[$i+$p]; $n++ }
    if ($n -eq 0) { continue }
    $s=$sum/$n
    if ($s -gt $best*1.02) { $best=$s; $bestP=$p }
  }
  return $bestP
}

$pX = Find-Pitch $colE 24 200
if ($pX -lt 0) { Write-Output "$(Split-Path $path -Leaf) : NO PITCH"; exit }
# Y pitch only searched near the X pitch — see GridDetector.findPitchNear.
$loY = [Math]::Max(24, [int]($pX * 0.75))
$hiY = [Math]::Min(200, [int]($pX * 1.25))
$pY = Find-Pitch $rowE $loY $hiY
$agree = ([Math]::Abs($pX - $pY) -le $pX * 0.12)
$pitch = if ($agree) { ($pX + $pY) / 2.0 } else { [double]$pX }
$span = [int]($pitch * 8)

# Start coordinate: scan the whole axis, scoring border strength minus energy just outside.
function Max-Win($e, $from, $to) {
  $m = 0; $a = [Math]::Max(0,$from); $b = [Math]::Min($e.Count-1,$to)
  for ($i=$a;$i -le $b;$i++){ if ($e[$i] -gt $m) { $m=$e[$i] } }
  return $m
}

function Score-Start($e, $pitch, $s, $span) {
  $first = Max-Win $e ($s-2) ($s+2)
  $last = Max-Win $e ([int]($s+$span)-2) ([int]($s+$span)+2)
  $beforeOut = Max-Win $e ([int]($s-$pitch*0.4)-2) ([int]($s-$pitch*0.4)+2)
  $afterOut = Max-Win $e ([int]($s+$span+$pitch*0.4)-2) ([int]($s+$span+$pitch*0.4)+2)
  $interior = 0.0
  for ($i=1;$i -le 7;$i++){ $idx=[int]($s+$i*$pitch); $interior += Max-Win $e ($idx-2) ($idx+2) }
  return ($first + $last) * 2.0 + $interior * 0.3 - ($beforeOut + $afterOut) * 0.5
}

function Best-Start($e, $pitch, $limit, $span) {
  $bestStart = -1; $bestScore = [double]::MinValue
  $step = [Math]::Max(1, [int]($pitch * 0.25))
  $s = 0
  while (($s + $span) -le $limit) {
    $score = Score-Start $e $pitch $s $span
    if ($score -gt $bestScore) { $bestScore = $score; $bestStart = $s }
    $s += $step
  }
  if ($bestStart -lt 0) { return -1 }
  $refined = $bestStart; $rScore = $bestScore
  for ($t = ($bestStart - $step); $t -le ($bestStart + $step); $t++) {
    if ($t -lt 0 -or ($t + $span) -gt $limit) { continue }
    $sc = Score-Start $e $pitch $t $span
    if ($sc -gt $rScore) { $rScore = $sc; $refined = $t }
  }
  return $refined
}

$left = Best-Start $colE $pitch $W $span
$top = Best-Start $rowE $pitch $H $span
$name = Split-Path $path -Leaf
Write-Output "$name : pitchX=$pX pitchY=$pY agree=$agree pitch=$([Math]::Round($pitch,1)) grid=$left,$top,$($span)x$($span)"
