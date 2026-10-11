Add-Type -AssemblyName System.Drawing
$P = 'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\build\promo2'
$out = 'C:\Users\H23_C5\Documents\deepseek-harness\default-workspace\vocaloid-android\dist'
$bold = [System.Drawing.FontStyle]::Bold
$cyan = [System.Drawing.Color]::FromArgb(255, 79, 195, 247)
$white = [System.Drawing.Color]::White
$grey = [System.Drawing.Color]::FromArgb(255, 186, 206, 220)
$shadow = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(160, 0, 0, 0))
function New-Cover {
  param([int]$W, [int]$H)
  $szTitle = [single]($H * 0.145); $szSub = [single]($H * 0.040)
  $szMid = [single]($H * 0.032); $szVer = [single]($H * 0.052)
  $bmp = New-Object System.Drawing.Bitmap $W, $H
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
  $bg = [System.Drawing.Image]::FromFile("$P\shots\s2.png")
  $s = [Math]::Max($W / $bg.Width, $H / $bg.Height)
  $dw = [int]($bg.Width * $s); $dh = [int]($bg.Height * $s)
  $dx = [int](($W - $dw) / 2); $dy = [int](($H - $dh) / 2)
  $g.DrawImage($bg, $dx, $dy, $dw, $dh)
  $bg.Dispose()
  $rect = New-Object System.Drawing.Rectangle 0, 0, $W, $H
  $c1 = [System.Drawing.Color]::FromArgb(232, 6, 12, 18)
  $c2 = [System.Drawing.Color]::FromArgb(90, 6, 12, 18)
  $br = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, $c1, $c2, 20.0
  $g.FillRectangle($br, $rect)
  $br.Dispose()
  $fT = New-Object System.Drawing.Font 'Microsoft YaHei', $szTitle, $bold
  $fS = New-Object System.Drawing.Font 'Microsoft YaHei', $szSub
  $fM = New-Object System.Drawing.Font 'Microsoft YaHei', $szMid
  $fV = New-Object System.Drawing.Font 'Microsoft YaHei', $szVer, $bold
  $x = [int]($W * 0.055); $y = [int]($H * 0.15)
  $g.DrawString('BriVoice', $fT, $shadow, ($x + 5), ($y + 5))
  $g.DrawString('BriVoice', $fT, $white.Clone(), $x, $y)
  $y = $y + [int]($H * 0.185)
  $g.DrawString('手机上的歌声合成与调音 · Android', $fS, [System.Drawing.Brushes]::LightSkyBlue, $x, $y)
  $y = $y + [int]($H * 0.070)
  $pen = New-Object System.Drawing.Pen $cyan, ([single]($H * 0.005))
  $g.DrawLine($pen, $x, $y, ($x + [int]($W * 0.28)), $y)
  $pen.Dispose()
  $y = $y + [int]($H * 0.050)
  $g.DrawString('v7.3', $fV, [System.Drawing.Brushes]::White, $x, $y)
  $y = $y + [int]($H * 0.080)
  $g.DrawString('制作 · AI · 教程 · 声库制作 · 工具库', $fM, [System.Drawing.Brushes]::Gainsboro, $x, $y)
  $ins = [System.Drawing.Image]::FromFile("$P\shots\s8.png")
  $iw = [int]($W * 0.44); $ih = [int]($iw * $ins.Height / $ins.Width)
  $ix = $W - $iw - [int]($W * 0.045); $iy = [int](($H - $ih) / 2)
  $g.DrawImage($ins, $ix, $iy, $iw, $ih)
  $bd = New-Object System.Drawing.Pen $cyan, ([single]($H * 0.004))
  $g.DrawRectangle($bd, $ix, $iy, $iw, $ih)
  $bd.Dispose(); $ins.Dispose(); $g.Dispose()
  return $bmp
}
$a = New-Cover -W 1920 -H 1080
$a.Save("$out\BriVoice_7.3_cover.png", [System.Drawing.Imaging.ImageFormat]::Png)
$a.Save("$out\BriVoice_7.3_cover.jpg", [System.Drawing.Imaging.ImageFormat]::Jpeg)
$a.Dispose()
$b = New-Cover -W 1280 -H 720
$b.Save("$out\BriVoice_7.3_cover_720p.jpg", [System.Drawing.Imaging.ImageFormat]::Jpeg)
$b.Dispose()
Get-ChildItem "$out\BriVoice_7.3_cover*" | ForEach-Object { Write-Output ($_.Name + ' ' + [math]::Round($_.Length/1KB) + ' KB') }