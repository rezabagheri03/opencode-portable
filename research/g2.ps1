param([string]$File,[string]$From,[int]$Len=8000,[int]$Skip=0)
$t = (Get-Content $File -Raw) -replace '(?s)<script.*?</script>',' ' -replace '(?s)<style.*?</style>',' ' -replace '<[^>]+>',' ' -replace '&quot;','"' -replace '&#39;',"'" -replace '&amp;','&' -replace '&lt;','<' -replace '&gt;','>' -replace '&nbsp;',' '
$t = $t -replace '\s+',' '
$i = $t.IndexOf($From)
if ($i -lt 0) { "NOT FOUND: $From"; exit }
$s = $i + $Skip
$len = [Math]::Min($Len, $t.Length - $s)
$t.Substring($s,$len)
