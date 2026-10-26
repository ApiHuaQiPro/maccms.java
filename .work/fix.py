p='backend/src/test/php/legacy_images_golden.php'
s=open(p,encoding='utf-8').read()
BS = chr(92)
s=s.replace(chr(9)+'hink'+BS+'Loader', BS+'think'+BS+'Loader')
assert chr(9) not in s and chr(7) not in s
open(p,'w',encoding='utf-8',newline='\n').write(s)
