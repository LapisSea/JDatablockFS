package com.lapissea.jorth.v2.lang;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.CodeStream;

import java.util.BitSet;
import java.util.regex.Pattern;

import static java.lang.Float.parseFloat;
import static java.lang.Integer.parseInt;

public class Tokenizer implements CodeStream, TokenSource{
	
	private static final BitSet SPECIALS=new BitSet();
	
	public static String escape(String src){
		var result=new StringBuilder(src.length()+5);
		for(int i=0;i<src.length();i++){
			var c=src.charAt(i);
			if(SPECIALS.get(c)||Character.isWhitespace(c)){
				result.append('\\');
			}
			result.append(c);
		}
		return result.toString();
	}
	
	static{
		"[]{}',;/<>".chars().forEach(SPECIALS::set);
	}
	
	private final CodeDestination dad;
	
	private CharSequence code;
	private int          pos;
	private int          line;
	
	private TokenSource transformed;
	
	public Tokenizer(CodeDestination dad, int line){
		this.dad=dad;
		this.line=line;
	}
	
	@Override
	public boolean hasMore(){
		return code!=null||peeked!=null;
	}
	
	@Override
	public void write(CharSequence code) throws MalformedJorthException{
		if(transformed==null) transformed=dad.transform(this);
		pos=0;
		this.code=code;
		skipWhitespace();
		while(hasMore()){
			dad.parse(transformed);
		}
		line++;
	}
	
	private void skipWhitespace(){
		if(pos==code.length()){
			code=null;
			return;
		}
		char c;
		while(true){
			c=code.charAt(pos);
			if(!Character.isWhitespace(c)) break;
			if(c=='\n'){
				line++;
			}
			
			pos++;
			if(pos==code.length()){
				code=null;
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
	
	private String scanInsideString() throws MalformedJorthException{
		if(code.charAt(pos)!='\'') throw new RuntimeException();
		pos++;
		
		StringBuilder sb=null;
		
		int     start =pos;
		boolean escape=false;
		
		for(int i=start;;i++){
			if(i==code.length()) throw new MalformedJorthException("String was opened but not closed");
			
			var c=code.charAt(i);
			
			if(escape){
				escape=false;
				if(sb==null) sb=new StringBuilder(i-start+16).append(code, start, i-1);
				sb.append(unescape(c));
				continue;
			}
			
			if(c=='\\'){
				escape=true;
				continue;
			}
			
			if(c=='\''){
				pos=i+1;
				try{
					if(sb!=null) return sb.toString();
					return code.subSequence(start, i).toString();
				}finally{
					skipWhitespace();
				}
			}
			if(sb!=null) sb.append(c);
		}
	}
	
	
	private static final Pattern HEX_NUM=Pattern.compile("^-?0[xX][0-9a-fA-F_]+$");
	private static final Pattern BIN_NUM=Pattern.compile("^-?0[bB][0-1_]+$");
	private static final Pattern DEC_NUM=Pattern.compile("^-?[0-9_]*\\.?[0-9]+[0-9_]*$");
	
	private int lastLine;
	
	@Override
	public int line(){
		return peeked!=null?peeked.line():lastLine;
	}
	
	
	private Token peeked;
	@Override
	public Token peekToken() throws MalformedJorthException{
		if(peeked==null) peeked=readToken();
		return peeked;
	}
	
	@Override
	public Token readToken() throws MalformedJorthException{
		if(peeked!=null){
			var p=peeked;
			peeked=null;
			return p;
		}
		if(code==null) throw new MalformedJorthException("Unexpected end of code");
		
		lastLine=this.line;
		if(code.charAt(pos)=='\''){
			String val=scanInsideString();
			return new Token.StrValue(lastLine, val);
		}
		
		String word;
		{
			int start=pos;
			int i    =start;
			
			StringBuilder sb    =null;
			boolean       escape=false;
			
			for(;i<code.length();i++){
				var c1=code.charAt(i);
				
				if(escape){
					escape=false;
					if(sb==null) sb=new StringBuilder(i-start+16).append(code, start, i-1);
					sb.append(unescape(c1));
					continue;
				}
				
				if(c1=='\\'){
					escape=true;
					continue;
				}
				
				if(Character.isWhitespace(c1)) break;
				if(SPECIALS.get(c1)){
					if(i==start){
						pos++;
						skipWhitespace();
						return new Token.SmolWord(line, c1);
					}
					break;
				}
				
				if(sb!=null) sb.append(c1);
			}
			
			if(start==i) throw new MalformedJorthException("Unexpected token");
			
			pos=i;
			if(start+1==i){
				var c=code.charAt(start);
				skipWhitespace();
				return new Token.SmolWord(line, c);
			}
			
			String rawWord;
			if(sb!=null) rawWord=sb.toString();
			else rawWord=code.subSequence(start, i).toString();
			skipWhitespace();
			word=rawWord;
		}
		
		var keyword=Keyword.MAP.get(word);
		if(keyword!=null){
			return new Token.KWord(lastLine, keyword);
		}
		
		if(word.equals("null")) return new Token.Null(lastLine);
		if(word.equals("true")) return new Token.Bool(lastLine, true);
		if(word.equals("false")) return new Token.Bool(lastLine, false);
		
		var c=word.charAt(0);
		if(c=='-'||c=='.'||(c>='0'&&c<='9')){
			if(HEX_NUM.matcher(word).matches()){
				return new Token.IntVal(lastLine, parseInt(cleanNumber(word, 2), 16));
			}
			if(BIN_NUM.matcher(word).matches()){
				return new Token.IntVal(lastLine, parseInt(cleanNumber(word, 2), 2));
			}
			if(DEC_NUM.matcher(word).matches()){
				var dec=cleanNumber(word, 0);
				if(dec.contains(".")){
					return new Token.FloatVal(lastLine, parseFloat(dec.replace("_", "")));
				}
				return new Token.IntVal(lastLine, parseInt(dec));
			}
		}
		
		return new Token.Word(lastLine, word);
	}
	
	private static String cleanNumber(String num, int prefix){
		if(prefix!=0){
			if(num.charAt(0)=='-'){
				num="-"+num.substring(prefix+1);
			}else{
				num=num.substring(prefix);
			}
		}
		num=num.replace("_", "");
		return num;
	}
}
