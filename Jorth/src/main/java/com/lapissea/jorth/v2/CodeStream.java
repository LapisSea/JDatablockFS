package com.lapissea.jorth.v2;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.lang.Tokenizer;
import com.lapissea.jorth.v2.lang.text.CharJoin;
import com.lapissea.jorth.v2.lang.text.CharSubview;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public interface CodeStream{
	
	static int resolveArg(String src, List<CharSequence> dest, Object[] objs, int iter, int start){
		for(int i = start; i<src.length(); i++){
			var c1 = src.charAt(i);
			if(c1 == '{'){
				int     len    = 2;
				boolean escape = false;
				var     c2     = src.charAt(i + 1);
				if(c2 == '!'){
					escape = true;
					c2 = src.charAt(i + len);
					len++;
				}
				
				int num = -1;
				
				while(c2>='0' && c2<='9'){
					int n = c2 - '0';
					if(num == -1) num = n;
					else{
						num = num*10 + n;
					}
					c2 = src.charAt(i + len);
					len++;
				}
				if(num == -1) num = iter;
				
				if(c2 == '}'){
					var str = Objects.toString(objs[num]);
					objs[num] = str;
					
					if(escape) str = Tokenizer.escape(str);
					dest.add(CharSubview.of(src, start, i));
					if(!str.isEmpty()) dest.add(str);
					return i + len;
				}
			}
		}
		throw new IllegalArgumentException("Could not find \"{}\"");
	}
	
	void write(CharSequence code) throws MalformedJorthException;
	
	default void write(String code, Object... objs) throws MalformedJorthException{
		int start = 0;
		var parts = new ArrayList<CharSequence>(objs.length*2 + 1);
		for(int i = 0; i<objs.length; i++){
			start = resolveArg(code, parts, objs, i, start);
		}
		if(start != code.length()){
			parts.add(new CharSubview(code, start, code.length()));
		}
		
		CharSequence join;
		if(parts.size() == 1) join = parts.get(0);
		else join = new CharJoin(parts);
		
		write(join);
	}
}
