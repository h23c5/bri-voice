$ErrorActionPreference='Continue'
$ff = 'C:\Program Files (x86)\FormatFactory\FFModules\Encoder\ffmpeg.exe'
$T = 'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\trailer'
$SRC = 'C:\Users\H23_C5\Downloads\Screenrecording_20261007_120602.mp4'
$parts = "$T\parts"
New-Item -ItemType Directory -Force -Path $parts | Out-Null
Remove-Item "$parts\*" -Force -ErrorAction SilentlyContinue
$V = @('-c:v','libx264','-preset','medium','-crf','20','-pix_fmt','yuv420p','-r','30')
$A = @('-c:a','aac','-b:a','128k','-ar','48000','-ac','1')
$SIL = @('-f','lavfi','-i','anullsrc=r=48000:cl=mono')

# intro and outro: PNG sequences
& $ff -y -framerate 30 -i "$T\cards\open%04d.png" @SIL -shortest @V @A -t 10 "$parts\p1.mp4" 2> "$T\log1.txt"
Write-Output ("intro: " + $LASTEXITCODE)
& $ff -y -framerate 30 -i "$T\cards\outro%04d.png" @SIL -shortest @V @A -t 12 "$parts\p7.mp4" 2> "$T\log7.txt"
Write-Output ("outro: " + $LASTEXITCODE)

# section cards (3 s each, silent)
foreach ($n in 1..5) {
  & $ff -y -framerate 30 -i "$T\cards\sec$n%04d.png" @SIL -shortest @V @A -t 3 "$parts\c$n.mp4" 2> "$T\logc$n.txt"
  Write-Output ("card $n : " + $LASTEXITCODE)
}

# clips with caption overlay and fades
$plan = @(@(1,46,30), @(2,82,34), @(3,34,22), @(4,112,30), @(5,2,34))
foreach ($p in $plan) {
  $n = $p[0]; $ss = $p[1]; $d = $p[2]
  $fadeOut = $d - 0.6
  $fc = "[0:v]scale=1280:-1,pad=1280:720:0:(oh-ih)/2,setsar=1,fade=t=in:st=0:d=0.5,fade=t=out:st=$fadeOut`:d=0.6[bg];[bg][1:v]overlay=0:0"
  & $ff -y -ss $ss -t $d -i $SRC -i "$T\cap$n.png" -filter_complex $fc -af "afade=t=in:st=0:d=0.5,afade=t=out:st=$fadeOut`:d=0.6" @V @A "$parts\v$n.mp4" 2> "$T\logv$n.txt"
  Write-Output ("clip $n at $ss s for $d s : " + $LASTEXITCODE)
}

# concat in order
$list = @('p1.mp4','c1.mp4','v1.mp4','c2.mp4','v2.mp4','c3.mp4','v3.mp4','c4.mp4','v4.mp4','c5.mp4','v5.mp4','p7.mp4')
$lines = $list | ForEach-Object { "file '$parts\$_'" }
Set-Content -Path "$T\list.txt" -Value $lines -Encoding ASCII
$out = 'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\BriVoice_release.mp4'
& $ff -y -f concat -i "$T\list.txt" -c copy "$out" 2> "$T\logcat.txt"
Write-Output ("concat: " + $LASTEXITCODE)
if (Test-Path $out) { Write-Output ("size: " + [math]::Round((Get-Item $out).Length/1MB,1) + " MB") }
& $ff -i $out 2> "$T\info.txt"
Get-Content "$T\info.txt" -Encoding Default | Select-String 'Duration|Stream' | Select-Object -First 3