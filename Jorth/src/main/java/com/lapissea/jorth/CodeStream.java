package com.lapissea.jorth;

import com.lapissea.jorth.lang.Tokenizer;
import com.lapissea.jorth.lang.text.CharJoin;
import com.lapissea.jorth.lang.text.CharSubview;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public interface CodeStream extends AutoCloseable{
	
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
		return -1;
	}
	
	CodeStream write(CharSequence code) throws MalformedJorth;
	
	default CodeStream write(String code, Object... objs) throws MalformedJorth{
		int start = 0;
		var parts = new ArrayList<CharSequence>(objs.length*2 + 1);
		int iter  = 0;
		while(true){
			var next = resolveArg(code, parts, objs, iter, start);
			iter++;
			if(next == -1){
				if(iter<objs.length){
					throw new IllegalArgumentException("Could not find \"{}\"");
				}
				break;
			}
			start = next;
		}
		if(start != code.length()){
			parts.add(new CharSubview(code, start, code.length()));
		}
		
		CharSequence join;
		if(parts.size() == 1) join = parts.get(0);
		else join = new CharJoin(parts);
		
		return write(join);
	}
	
	default void addImport(Class<?> clazz){
		addImport(clazz.getName());
	}
	void addImport(String className);
	void addImportAs(String className, String name);
	default void addImportAs(Class<?> clazz, String name){
		addImportAs(clazz.getName(), name);
	}
	
	@Override
	void close() throws MalformedJorth;
}
