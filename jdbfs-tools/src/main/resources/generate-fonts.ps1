
get-childitem '.' -recurse -force -Name -Filter *font.ttf | ForEach-Object{
    $File = $_
    $par=($File -split '\\')[0..(($path -split '\\').count)] -join '\'
    Start-Process 'msdf-atlas-gen.exe' -ArgumentList "-font $File -size 45 -pxrange 3 -pxpadding 1 -imageout atlas.png -json atlas.json -charset charset.txt -potr -coloringstrategy distance"
    Start-Process 'msdf-atlas-gen.exe' -ArgumentList "-font $File -size 100 -imageout $par\mask.png -json $par\mask.json -charset charset.txt -potr -type softmask"
}