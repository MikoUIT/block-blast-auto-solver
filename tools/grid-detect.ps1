Add-Type -AssemblyName System.Drawing
# Offline port of the multi-candidate autocorrelation GridDetector. Prints pitch candidates and the
# chosen board geometry so the algorithm can be validated against real captured frames.
# Usage: powershell -File tools\grid-detect.ps1 <colour.jpg> [scale]

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

function Max-Win($e, $from, $to) { $m=0; $a=[Math]::Max(0,$from); $b=[Math]::Min($e.Count-1,$to)
  for ($i=$a;$i -le $b;$i++){ if ($e[$i] -gt $m) { $m=$e[$i] } }; return $m }

function Best-Phase($e, $pitch) { $bo=0; $bs=[long]::MinValue; $st=[Math]::Max(1,[int]$pitch)
  for ($o=0;$o -lt $st;$o++){ $s=[long]0; $k=0; while ($true){ $idx=[int]($o+$k*$pitch); if ($idx -ge $e.Count){break}; $s+=$e[$idx]; $k++ }
    if ($s -gt $bs){ $bs=$s; $bo=$o } }; return $bo }

function Score-Start($e, $pitch, $s, $span) {
  $first=Max-Win $e ($s-2) ($s+2); $last=Max-Win $e ([int]($s+$span)-2) ([int]($s+$span)+2)
  $bOut=Max-Win $e ([int]($s-$pitch*0.4)-2) ([int]($s-$pitch*0.4)+2)
  $aOut=Max-Win $e ([int]($s+$span+$pitch*0.4)-2) ([int]($s+$span+$pitch*0.4)+2)
  $int=0.0; for ($i=1;$i -le 7;$i++){ $idx=[int]($s+$i*$pitch); $int+=Max-Win $e ($idx-2) ($idx+2) }
  return ($first+$last)*2.0 + $int*0.3 - ($bOut+$aOut)*0.5 }

function Best-Start($e,$pitch,$limit,$span){ $bs=-1;$bsc=[double]::MinValue; $st=[Math]::Max(1,[int]($pitch*0.25)); $s=0
  while (($s+$span) -le $limit){ $sc=Score-Start $e $pitch $s $span; if ($sc -gt $bsc){$bsc=$sc;$bs=$s}; $s+=$st }
  if ($bs -lt 0){return -1}; $r=$bs;$rs=$bsc
  for ($t=($bs-$st);$t -le ($bs+$st);$t++){ if ($t -lt 0 -or ($t+$span) -gt $limit){continue}
    $sc=Score-Start $e $pitch $t $span; if ($sc -gt $rs){$rs=$sc;$r=$t} }; return $r }

function Board-Quality($colE,$rowE,$left,$top,$pitch){
  $cm=($colE|Measure-Object -Maximum).Maximum; if($cm -le 0){$cm=1}
  $rm=($rowE|Measure-Object -Maximum).Maximum; if($rm -le 0){$rm=1}
  $tol=[Math]::Max(2,[int]($pitch*0.15)); $v=0.0;$h=0.0
  for($i=0;$i -le 8;$i++){ $x=[int]($left+$i*$pitch); $y=[int]($top+$i*$pitch)
    $v += (Max-Win $colE ($x-$tol) ($x+$tol))/$cm; $h += (Max-Win $rowE ($y-$tol) ($y+$tol))/$rm }
  $struct=($v+$h)/18.0; $size=($pitch*8.0)/1080.0; if($size -gt 1){$size=1}
  return $struct*0.7 + $size*0.3 }

function Pitch-Candidates($e,$minP,$maxP,$limit){
  $mean=($e|Measure-Object -Average).Average; $c=New-Object 'double[]' $e.Count
  for($i=0;$i -lt $e.Count;$i++){$c[$i]=$e[$i]-$mean}
  $norm=0.0; for($i=0;$i -lt $c.Count;$i++){$norm+=$c[$i]*$c[$i]}; $norm=$norm/$c.Count
  if($norm -le 0){return @()}
  $curve=New-Object 'double[]' ($maxP+2)
  for($p=$minP;$p -le $maxP;$p++){ $sum=0.0;$n=0; for($i=0;$i+$p -lt $c.Count;$i++){$sum+=$c[$i]*$c[$i+$p];$n++}
    $curve[$p]= if($n -eq 0){0.0}else{$sum/$n} }
  $peaks=@()
  for($p=$minP;$p -le $maxP;$p++){ $v=$curve[$p]
    $l= if($p-1 -ge $minP){$curve[$p-1]}else{[double]::NegativeInfinity}
    $r= if($p+1 -le $maxP){$curve[$p+1]}else{[double]::NegativeInfinity}
    if($v -ge $l -and $v -ge $r -and $v -gt 0){ $peaks += [pscustomobject]@{p=$p;v=$v} } }
  $peaks = $peaks | Sort-Object v -Descending
  $chosen=@(); $minSep=[int](0.5*$minP)
  foreach($pk in $peaks){ if(@($chosen | Where-Object { [Math]::Abs($_.p - $pk.p) -lt $minSep }).Count -gt 0){continue}
    $chosen += [pscustomobject]@{p=$pk.p; strength=[Math]::Min(1.0,$pk.v/$norm)}; if($chosen.Count -ge $limit){break} }
  return $chosen }

$cands = Pitch-Candidates $colE 24 ([Math]::Min([int]($W/8),200)) 6
$name = Split-Path $path -Leaf
if (@($cands).Count -eq 0){ Write-Output "$name : NO CANDIDATES"; exit }
$candStr = ($cands | ForEach-Object { "$($_.p)(s=$([Math]::Round($_.strength,2)))" }) -join " "
Write-Output "$name : candidates X: $candStr"

$best=$null; $bestScore=0.0; $bestPitch=0
foreach($cx in $cands){
  $pX=$cx.p
  $loY=[Math]::Max(24,[int]($pX*0.75)); $hiY=[Math]::Min(200,[int]($pX*1.25))
  $cys = Pitch-Candidates $rowE $loY $hiY 1
  if (@($cys).Count -eq 0){continue}
  $pY=$cys[0].p
  $agree=([Math]::Abs($pX-$pY) -le $pX*0.12)
  $pitch= if($agree){($pX+$pY)/2.0}else{[double]$pX}
  $span=[int]($pitch*8)
  if ($span -gt [Math]::Min($W,$H)){continue}
  $left = Best-Start $colE $pitch $W $span
  $top = Best-Start $rowE $pitch $H $span
  if ($left -lt 0 -or $top -lt 0){continue}
  $q = Board-Quality $colE $rowE $left $top $pitch
  if ($q -gt $bestScore){ $bestScore=$q; $best="$left,$top,$($span)x$($span)"; $bestPitch=$pitch }
}
if ($null -eq $best){ Write-Output "$name : NOT DETECTED" } else { Write-Output "$name : grid=$best pitch=$([Math]::Round($bestPitch,1)) quality=$([Math]::Round($bestScore,3))" }
