package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.CodeDestination;
import com.lapissea.jorth.lang.Token;
import com.lapissea.jorth.lang.TokenSource;
import com.lapissea.jorth.lang.Tokenizer;
import com.lapissea.jorth.lang.text.CharJoin;
import com.lapissea.jorth.lang.text.CharSubview;
import com.lapissea.util.TextUtil;

import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public interface CodeStream extends AutoCloseable{
	
	static CharSequence processFragment(String code, Object... objs){
		while(true){
			var res = resolveTemplate(code, objs);
			if(res.isEmpty()) break;
			code = res.get();
		}
		
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
		
		if(parts.size() == 1) return parts.getFirst();
		return new CharJoin(parts);
	}
	
	private static Optional<String> resolveTemplate(String src, Object[] objs){
		for(int i = 0; i<src.length(); i++){
			if(src.charAt(i) != 't') continue;
			
			var tfor = "template-for";
			if(
				src.length()<=i + tfor.length() + 2 ||
				!src.regionMatches(i, tfor, 0, tfor.length()) ||
				!Character.isWhitespace(src.charAt(i + tfor.length()))
			) continue;
			
			return Optional.of(unwrapTemplate(src, objs, i, tfor));
		}
		return Optional.empty();
	}
	
	private static String unwrapTemplate(String src, Object[] objs, int start, String forTrigger){
		var result = new StringBuilder();
		result.append(src, 0, start);
		
		try(var t = new Tokenizer(new CodeDestination(){
			record Part(boolean isKey, boolean inString, String val){ }
			@Override
			protected void parse(TokenSource source) throws MalformedJorth{
				var nameToken = source.readToken();
				var valName = switch(nameToken){
					case Token.Word w -> w.value();
					default -> throw new MalformedJorth("Name can not be \"" + nameToken.requireAs(Token.Word.class).value() + '"');
				};
				source.requireWord("in");
				var vals = readVals(source);
				source.requireWord("start");
				
				var template = readTemplate(source, valName);
				
				for(var val : vals){
					boolean inString = false;
					for(var part : template){
						if(part.isKey){
							var obj = getVal(val, part.val);
							var str = objToJorthStr(obj, !(obj instanceof Type));
							if(part.inString){
								result.append(str.replace("'", "\\'"));
								inString = true;
							}else{
								if(inString) inString = false;
								else result.append(' ');
								result.append(str);
							}
						}else{
							if(inString) inString = false;
							else result.append(' ');
							result.append(part.val);
						}
					}
				}
				
				result.append(' ').append(source.restRaw());
			}
			
			private static Object getVal(Object val, String key){
				if(key.isEmpty()) return val;
				try{
					var typ = val.getClass();
					if(typ.isRecord()){
						var mth = typ.getMethod(key);
						return mth.invoke(val);
					}
					try{
						var f = typ.getField(key);
						return f.get(val);
					}catch(NoSuchFieldException e){
						try{
							var f = typ.getMethod("get" + TextUtil.firstToUpperCase(key));
							return f.invoke(val);
						}catch(NoSuchMethodException e1){ }
						var f = typ.getMethod(key);
						return f.invoke(val);
					}
					
				}catch(ReflectiveOperationException e){
					throw new RuntimeException("Unable to find key \"" + key + "\" in " + val);
				}
			}
			
			private List<Part> readTemplate(TokenSource source, String valPart) throws MalformedJorth{
				var part     = new StringBuilder();
				var template = new ArrayList<Part>();
				int line     = 0, depth = 1;
				
				Runnable pushPart = () -> {
					if(part.isEmpty()) return;
					template.add(new Part(false, false, part.toString()));
					part.setLength(0);
				};
				
				loop:
				while(true){
					if(source.line() != line){
						line = source.line();
						part.append('\n');
					}
					var tok = source.readToken();
					switch(tok){
						case Token.KWord w -> {
							switch(w.keyword()){
								case START -> depth++;
								case END -> {
									pushPart.run();
									depth--;
								}
							}
							if(depth == 0) break loop;
						}
						case Token.Word w when w.value().startsWith(valPart) -> {
							var len = valPart.length();
							if(w.value().length()>len){
								var c = w.value().charAt(len);
								if(c == '.'){
									len++;
									if(w.value().length() == len){
										break;
									}
								}
							}
							var key = w.value().substring(len);
							pushPart.run();
							template.add(new Part(true, false, key));
							continue;
						}
						case Token.StrValue s -> {
							var val = "'" + s.value() + "'";
							int i   = 0, head = 0;
							for(; i<=val.length() - valPart.length(); i++){
								if(val.regionMatches(i, valPart, 0, valPart.length())){
									int keyStart = i + valPart.length(), keyEnd = keyStart;
									if(val.length()>keyEnd){
										if(val.charAt(keyEnd) == '.'){
											keyStart++;
											keyEnd++;
											
											while(val.length()>keyEnd && Character.isJavaIdentifierPart(val.charAt(keyEnd))){
												keyEnd++;
											}
										}else{
											//Check if the name is part of a larger word
											if(Character.isJavaIdentifierPart(val.charAt(keyEnd))){
												continue;
											}
										}
									}
									
									var start = val.substring(0, i);
									var key   = val.substring(keyStart, keyEnd);
									
									i = head = keyEnd;
									
									part.append(start);
									pushPart.run();
									template.add(new Part(true, true, key));
								}
							}
							var end = val.substring(head);
							part.append(end);
							continue;
						}
						default -> { }
					}
					part.append(tok.requireAs(Token.Word.class).value()).append(' ');
				}
				return template;
			}
			
			private List<Object> readVals(TokenSource source) throws MalformedJorth{
				var listArgs = source.bracketSet('{');
				if(listArgs.contents().isEmpty()) throw new MalformedJorth("For args needed");
				
				List<Object> vals = new ArrayList<>();
				for(var indexTok : listArgs.contents()){
					var idx = indexTok.requireAs(Token.NumToken.IntVal.class).value();
					
					var l = Objects.requireNonNull(objs[idx], "Arguments can not be null");
					if(l instanceof Iterable<?> col) col.forEach(vals::add);
					else if(l instanceof Stream<?> s) s.forEach(vals::add);
					else if(l.getClass().isArray()){
						for(int i = 0, len = Array.getLength(l); i<len; i++){
							vals.add(Array.get(l, i));
						}
					}else{
						throw new IllegalArgumentException(l.getClass().getName() + " is not an iterable or array or stream");
					}
				}
				for(Object val : vals){
					if(val == null) throw new NullPointerException("Source elements can not be null");
				}
				return vals;
			}
			
			@Override
			public void addImport(String clasName){ }
			@Override
			public void addImportAs(String clasName, String name){ }
		}, 0)){
			t.write(new CharSubview(src, start + forTrigger.length(), src.length()));
		}catch(
			 Throwable e){
			throw new RuntimeException(e);
		}
		
		return result.toString();
	}
	
	private static int resolveArg(String src, List<CharSequence> dest, Object[] objs, int iter, int start){
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
					var str = objToJorthStr(objs[num], escape);
					
					if(escape) str = Tokenizer.escape(str);
					if(start != i) dest.add(CharSubview.of(src, start, i));
					if(!str.isEmpty()) dest.add(str);
					return i + len;
				}
			}
		}
		return -1;
	}
	
	private static String objToJorthStr(Object obj, boolean escape){
		if(obj instanceof Type t){
			if(escape) throw new IllegalArgumentException("Types should not be escaped");
			return JorthUtils.toJorthGeneric(t);
		}
		return Objects.toString(obj);
	}
	
	CodeStream write(CharSequence code) throws MalformedJorth;
	
	default CodeStream write(String code, Object... objs) throws MalformedJorth{
		return write(processFragment(code, objs));
	}
	
	default void addImports(Class<?>... classes){
		for(Class<?> clazz : classes){
			addImport(clazz);
		}
	}
	default void addImport(Class<?> clazz){
		addImport(clazz.getName());
	}
	void addImport(String className);
	void addImportAs(String className, String name);
	default void addImportAs(Class<?> clazz, String name){
		addImportAs(clazz.getName(), name);
	}
	
	interface CodePart extends AutoCloseable{
		@Override
		void close() throws MalformedJorth;
	}
	CodePart codePart();
	
	@Override
	void close();
}
