# Regenerate with: PATH="$PWD/dist/native:$PATH" vhs docs/demo/demo.tape   (run from the repository root)
Output docs/demo/demo.gif

Set Shell "bash"
Set FontSize 16
Set Width 840
Set Height 520
Set Theme "Dracula"
Set Padding 20
Set TypingSpeed 35ms

Hide
Type "PS1='$ ' && clear"
Enter
Show

# 1. A sketch value copied to the clipboard (the copy itself is not shown)
Hide
Type "printf %s 'AgEHDAMIBQjL18IEK/L7BoYv+Q11gWYHgbxdBg==' | pbcopy"
Enter
Show
Type "pbpaste | sketchprobe"
Sleep 400ms
Enter
Sleep 3s

# 2. A MongoDB binary field, pasted as it is
Hide
Type "clear"
Enter
Show
Type `echo '{ "$binary" : "AQEKAwMFAAA=", "$type" : "00" },' | sketchprobe`
Sleep 400ms
Enter
Sleep 3s

# 3. A heavy-hitters (Frequency) sketch from a file
Hide
Type "clear && cd fixtures/corpus/field/frequency && clear"
Enter
Show
Type "sketchprobe --file frequency_strings_platforms.bin | head -21"
Sleep 400ms
Enter
Sleep 4s

# 4. Something that is not a sketch: one reason, one next step
Hide
Type "clear"
Enter
Show
Type "echo 'hello' | sketchprobe"
Sleep 400ms
Enter
Sleep 4s
