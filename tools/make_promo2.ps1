# BriVoice 7.3 promo film - needs ffmpeg in PATH (or pass -Ffmpeg <path>)
param([string]$Ffmpeg = 'ffmpeg')
$base = 'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android'
$T = "$base\build\trailer"; $P = "$base\build\promo2"
Add-Type -AssemblyName System.Drawing
New-Item -ItemType Directory -Force -Path "$P\jpg","$P\clips" | Out-Null
foreach ($n in 's2','s8','s7') {
  $im = [System.Drawing.Image]::FromFile("$P\shots\$n.png")
  $bm = New-Object System.Drawing.Bitmap 1920, 1080
  $g = [System.Drawing.Graphics]::FromImage($bm); $g.DrawImage($im, 0, 0, 1920, 1080)
  $bm.Save("$P\jpg\$n.jpg", [System.Drawing.Imaging.ImageFormat]::Jpeg)
  $g.Dispose(); $bm.Dispose(); $im.Dispose()
}
$all = Get-ChildItem "$T\jpg2" -Filter *.jpg | Sort-Object Name
$list = @(@{f=$all[40].FullName;d=8}, @{f="$P\jpg\s2.jpg";d=11}, @{f=$all[300].FullName;d=8},
          @{f="$P\jpg\s8.jpg";d=12}, @{f="$P\jpg\s7.jpg";d=9}, @{f=$all[-1].FullName;d=10})
$i=0; $names=@()
foreach ($c in $list) {
  $o = "$P\clips\c$i.mp4"; $d = $c.d; $st = $d - 1.0
  & $Ffmpeg -y -loop 1 -i $c.f -t $d -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:black,fade=t=in:st=0:d=0.8,fade=t=out:st=$st:d=0.8,format=yuv420p" -r 30 -c:v libx264 -preset veryfast -crf 20 $o 2>&1 | Out-Null
  if (Test-Path $o) { $names += "file '$($o.Replace('\','/'))'" }; $i++
}
$names | Set-Content "$P\list.txt" -Encoding ASCII
& $Ffmpeg -y -f concat -safe 0 -i "$P\list.txt" -c copy "$P\video.mp4" 2>&1 | Out-Null
& $Ffmpeg -y -i "$P\video.mp4" -i "$T\music.wav" -i "$T\sfx.wav" -filter_complex "[1:a]volume=0.8[m];[2:a]volume=0.9[s];[m][s]amix=inputs=2:duration=first[a]" -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -shortest "$base\dist\BriVoice_7.3_promo.mp4" 2>&1 | Out-Null
Get-Item "$base\dist\BriVoice_7.3_promo.mp4" | ForEach-Object { 'OUT ' + [math]::Round($_.Length/1MB,2) + ' MB' }