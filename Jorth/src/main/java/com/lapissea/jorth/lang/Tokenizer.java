package com.lapissea.jorth.lang;

import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.exceptions.EndOfCode;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.type.Access;
import com.lapissea.jorth.lang.type.KeyedEnum;
import com.lapissea.jorth.lang.type.Visibility;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

import static java.lang.Float.parseFloat;
import static java.lang.Integer.parseInt;

public class Tokenizer implements CodeStream, TokenSource{
	
	private static final BitSet SPECIALS = new BitSet();
	
	static{
		"[]{}',;/<>@".chars().forEach(SPECIALS::set);
	}
	
	public static String escape(String src){
		var result = new StringBuilder(src.length() + 5);
		for(int i = 0; i<src.length(); i++){
			var c = src.charAt(i);
			if(SPECIALS.get(c) || Character.isWhitespace(c)){
				result.append('\\');
			}
			result.append(c);
		}
		return result.toString();
	}
	
	private final CodeDestination dad;
	
	private final Deque<CharSequence> codeBuffer = new ArrayDeque<>();
	
	private CharSequence code;
	private int          pos;
	private int          line;
	
	private TokenSource transformed;
	
	public Tokenizer(CodeDestination dad, int line){
		this.dad = dad;
		this.line = line;
	}
	
	private void parseExisting() throws MalformedJorth{
		while(hasMore()){
			dad.parse(transformed);
		}
	}
	
	@Override
	public boolean hasMore(){
		return code != null || peeked != null || !codeBuffer.isEmpty();
	}
	
	@Override
	public String restRaw() throws MalformedJorth{
		List<CharSequence> parts = new ArrayList<>();
		
		var peek = peeked;
		if(peek != null){
			peeked = null;
			parts.add(peek.requireAs(Token.Word.class).value());
		}
		
		if(code != null){
			if(pos != 0) parts.add("\n");
			parts.add(code.subSequence(pos, code.length()));
			code = null;
			pos = 0;
		}
		for(var p : codeBuffer){
			parts.add("\n");
			parts.add(p);
		}
		codeBuffer.clear();
		return String.join(" ", parts);
	}
	
	private boolean buffering;
	
	@Override
	public void write(CharSequence code) throws MalformedJorth{
		if(transformed == null) transformed = dad.transform(this);
		codeBuffer.addLast(code);
		if(!buffering){
			parseExisting();
		}
	}
	
	@Override
	public CodePart codePart(){
		boolean lastBuffering = buffering;
		buffering = true;
		return () -> {
			buffering = lastBuffering;
			parseExisting();
		};
	}
	
	private void skipWhitespace(){
		if(pos == code.length()){
			code = null;
			return;
		}
		char c;
		while(true){
			c = code.charAt(pos);
			if(!Character.isWhitespace(c)) break;
			if(c == '\n'){
				line++;
			}
			
			pos++;
			if(pos == code.length()){
				code = null;
				break;
			}
		}
	}
	
	private static char unescape(char c){
		return switch(c){
			case 'n' -> '\n';
			case 'r' -> '\r';
			case 't' -> '\t';
			default -> c;
		};
	}
	
	private String scanInsideString() throws MalformedJorth{
		if(code.charAt(pos) != '\'') throw new RuntimeException();
		pos++;
		
		StringBuilder sb = null;
		
		int     start  = pos;
		boolean escape = false;
		
		for(int i = start; ; i++){
			if(i == code.length()) throw new MalformedJorth("String was opened but not closed");
			
			var c = code.charAt(i);
			
			if(escape){
				escape = false;
				if(sb == null) sb = new StringBuilder(i - start + 16).append(code, start, i - 1);
				sb.append(unescape(c));
				continue;
			}
			
			if(c == '\\'){
				escape = true;
				continue;
			}
			
			if(c == '\''){
				pos = i + 1;
				try{
					if(sb != null) return sb.toString();
					return code.subSequence(start, i).toString();
				}finally{
					skipWhitespace();
				}
			}
			if(sb != null) sb.append(c);
		}
	}
	
	
	private static final Pattern HEX_NUM = Pattern.compile("^-?0[xX][0-9a-fA-F_]+$");
	private static final Pattern BIN_NUM = Pattern.compile("^-?0[bB][0-1_]+$");
	private static final Pattern DEC_NUM = Pattern.compile("^-?[0-9_]*\\.?[0-9]+[0-9_]*$");
	
	private int lastLine;
	
	@Override
	public int line(){
		return peeked != null? peeked.line() : lastLine;
	}
	
	
	private Token peeked;
	@Override
	public Token peekToken(boolean required) throws MalformedJorth{
		if(peeked == null) peeked = readToken(required);
		return peeked;
	}
	
	@Override
	public Token readToken(boolean required) throws MalformedJorth{
		if(peeked != null){
			var p = peeked;
			peeked = null;
			return p;
		}
		
		while(code == null){
			if(!codeBuffer.isEmpty()){
				code = codeBuffer.removeFirst();
				pos = 0;
				line++;
				skipWhitespace();
				continue;
			}
			
			if(!hasMore()){
				if(required){
					throw new EndOfCode();
				}
				return null;
			}
		}
		
		lastLine = this.line;
		if(code.charAt(pos) == '\''){
			String val = scanInsideString();
			return new Token.StrValue(lastLine, val);
		}
		
		String word;
		{
			int start = pos;
			int i     = start;
			
			StringBuilder sb     = null;
			boolean       escape = false;
			
			for(; i<code.length(); i++){
				var c1 = code.charAt(i);
				
				if(escape){
					escape = false;
					if(sb == null) sb = new StringBuilder(i - start + 16).append(code, start, i - 1);
					sb.append(unescape(c1));
					continue;
				}
				
				if(c1 == '\\'){
					escape = true;
					continue;
				}
				
				if(Character.isWhitespace(c1)) break;
				if(c1 == '?'){
					if(i == start && (code.length()>i + 1 && code.charAt(i + 1) != '?')){
						pos++;
						skipWhitespace();
						return new Token.Wildcard(lastLine);
					}
				}
				if(SPECIALS.get(c1)){
					if(i == start){
						pos++;
						skipWhitespace();
						return smol(c1);
					}
					break;
				}
				
				if(sb != null) sb.append(c1);
			}
			
			if(start == i){
				throw new MalformedJorth("Unexpected token");
			}
			
			pos = i;
			if(start + 1 == i){
				var c = code.charAt(start);
				skipWhitespace();
				return smol(c);
			}
			
			String rawWord;
			if(sb != null) rawWord = sb.toString();
			else rawWord = code.subSequence(start, i).toString();
			skipWhitespace();
			word = rawWord;
		}
		
		var keyword = Keyword.LOOKUP.get(word, false);
		if(keyword != null){
			return new Token.KWord(lastLine, keyword);
		}
		
		var vis = KeyedEnum.getOptional(Visibility.class, word);
		if(vis != null) return new Token.EWord<>(lastLine, vis);
		
		var acc = KeyedEnum.getOptional(Access.class, word);
		if(acc != null) return new Token.EWord<>(lastLine, acc);
		
		
		switch(word){
			case "true" -> { return new Token.Bool(lastLine, true); }
			case "false" -> { return new Token.Bool(lastLine, false); }
		}
		
		var c = word.charAt(0);
		if(c == '-' || c == '.' || (c>='0' && c<='9')){
			if(HEX_NUM.matcher(word).matches()){
				return new Token.NumToken.IntVal(lastLine, parseInt(cleanNumber(word, 2), 16));
			}
			if(BIN_NUM.matcher(word).matches()){
				return new Token.NumToken.IntVal(lastLine, parseInt(cleanNumber(word, 2), 2));
			}
			if(DEC_NUM.matcher(word).matches()){
				var dec = cleanNumber(word, 0);
				if(dec.contains(".")){
					return new Token.NumToken.FloatVal(lastLine, parseFloat(dec.replace("_", "")));
				}
				return new Token.NumToken.IntVal(lastLine, parseInt(dec));
			}
		}
		
		return new Token.Word(lastLine, word);
	}
	
	private Token smol(char c){
		if(c>='0' && c<='9') return new Token.NumToken.IntVal(lastLine, c - '0');
		
		var k = Keyword.LOOKUP.getOptional(c);
		if(k != null) return new Token.KWord(lastLine, k);
		
		return new Token.SmolWord(lastLine, c);
	}
	
	private static String cleanNumber(String num, int prefix){
		if(prefix != 0){
			if(num.charAt(0) == '-'){
				num = "-" + num.substring(prefix + 1);
			}else{
				num = num.substring(prefix);
			}
		}
		num = num.replace("_", "");
		return num;
	}
	
	@Override
	public void addImport(String clasName){
		dad.addImport(clasName);
	}
	@Override
	public void addImportAs(String className, String name){
		dad.addImportAs(className, name);
	}
	@Override
	public void close(){
	}
}
