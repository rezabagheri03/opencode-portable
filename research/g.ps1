param([string]$File,[string]$Pattern,[int]$Ctx=500,[int]$Max=6)
$raw = Get-Content $File -Raw
$t = $raw -replace '(?s)<script.*?</script>',' ' -replace '(?s)<style.*?</style>',' ' -replace '<[^>]+>',' ' -replace '&quot;','"' -replace '&#39;',"'" -replace '&amp;','&' -replace '&lt;','<' -replace '&gt;','>' -replace '&nbsp;',' '
$t = $t -replace '\s+',' '
$ms = [regex]::Matches($t, $Pattern, 'IgnoreCase')
"### $([System.IO.Path]::GetFileName($File)) :: /$Pattern/ -> $($ms.Count) hits"
$i=0
foreach ($m in $ms) { if ($i -ge $Max) { break }
  $s = [Math]::Max(0, $m.Index - $Ctx); $len = [Math]::Min($t.Length - $s, $m.Length + 2*$Ctx)
  "[" + $i + "] ..." + $t.Substring($s,$len) + "..."
  ""
  $i++
}
