package com.lapissea.jorth;

import com.lapissea.jorth.lang.Token;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeBiConsumer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public class JorthWriter implements AutoCloseable{
	
	public interface CharIterator{
		
		class Sequence implements CharIterator{
			
			private final CharSequence data;
			private       int          cursor;
			
			public Sequence(CharSequence data){
				this.data=requireNonNull(data);
			}
			
			@Override
			public boolean hasNext(){
				return cursor<data.length();
			}
			@Override
			public char next(){
				return data.charAt(cursor++);
			}
			@Override
			public String toString(){
				return data.toString().substring(cursor);
			}
		}
		
		boolean hasNext();
		char next();
	}
	
	private final UnsafeBiConsumer<JorthWriter, Token, MalformedJorthException> rawTokens;
	private final Map<String, String>                                           overrides=new HashMap<>();
	
	private int line;
	JorthWriter(int startingLine, UnsafeBiConsumer<JorthWriter, Token, MalformedJorthException> rawTokens){
		this.line=startingLine;
		this.rawTokens=rawTokens;
	}
	
	private CharSequence inlineCodeValues(CharIterator data, String[] values) throws MalformedJorthException{
		return inlineCodeValues(data, values, false);
	}
	private CharSequence inlineCodeValues(CharIterator data, String[] values, boolean inString) throws MalformedJorthException{
		StringBuilder sb=new StringBuilder();
		while(data.hasNext()){
			char c=data.next();
			
			if(!inString&&c=='\''){
				StringBuilder stringContents=new StringBuilder();
				transferStringContents(data, stringContents);
				var s=inlineCodeValues(seq(stringContents), values, true);
				sb.append('\'');
				sb.append(s);
				sb.append('\'');
				continue;
			}
			
			if(c=='#'){
				var token=readArgToken(data);
				if(token.index>=values.length){
					throw new MalformedJorthException("Argument index is out of bounds! Index: "+token.index+" value count: "+values.length);
				}
				
				if(token.type==CodeArg.Type.RAW){
					sb.append(values[token.index]);
					continue;
				}
				if(token.type==CodeArg.Type.TOKEN){
					sb.append(escapeToken(values[token.index]));
					continue;
				}
				
				throw new ShouldNeverHappenError(token.type+"");
			}
			if(inString&&c=='\'') sb.append('\\');
			sb.append(c);
		}
		return sb;
	}
	
	private CharSequence escapeToken(CharSequence value){
		StringBuilder sb=new StringBuilder(value.length()+4);
		for(int i=0;i<value.length();i++){
			var c=value.charAt(i);
			if(Character.isWhitespace(c)){
				sb.append('\\');
			}
			sb.append(switch(c){
				case '\t' -> 't';
				case '\n' -> 'n';
				case '\f' -> 'f';
				case '\r' -> 'r';
				default -> c;
			});
		}
		return sb;
	}
	
	private void transferStringContents(CharIterator data, StringBuilder dest) throws MalformedJorthException{
		char c;
		if(!data.hasNext()) stringFail();
		boolean hasHash=false;
		while(true){
			c=data.next();
			if(hasHash){
				dest.append(c);
				hasHash=false;
				continue;
			}
			if(c=='\\'){
				hasHash=true;
				continue;
			}
			if(c=='\'') break;
			dest.append(c);
			if(!data.hasNext()) stringFail();
		}
	}
	private void stringFail() throws MalformedJorthException{
		throw new MalformedJorthException("String literal opened but not closed");
	}
	private void unexpectedEnd() throws MalformedJorthException{
		throw new MalformedJorthException("Unexpected end of file occurred");
	}
	
	record CodeArg(Type type, int index){
		enum Type{
			TOKEN, RAW;
			
			private final String lower=toString().toLowerCase();
			
			private static final int MIN_LEN=Arrays.stream(Type.values()).mapToInt(t->t.name().length()).min().orElseThrow();
			private static final int MAX_LEN=Arrays.stream(Type.values()).mapToInt(t->t.name().length()).max().orElseThrow();
		}
	}
	
	private CodeArg readArgToken(CharIterator data) throws MalformedJorthException{
		var type=readType(data);
		var c   =readOrUnexpected(data);
		if(c!='(') throw new MalformedJorthException("Expected \"(number)\" but got "+c+" ...");
		StringBuilder numStr=new StringBuilder();
		while(true){
			c=readOrUnexpected(data);
			if(Character.isWhitespace(c)) continue;
			break;
		}
		
		while(true){
			if(!Character.isDigit(c)) break;
			numStr.append(c);
			c=readOrUnexpected(data);
		}
		while(true){
			if(Character.isWhitespace(c)){
				c=readOrUnexpected(data);
				continue;
			}
			break;
		}
		if(c!=')'){
			throw new MalformedJorthException("Expected \"(number)\" but got ("+numStr+c);
		}
		if(numStr.isEmpty()) throw new MalformedJorthException("Expected \"(number)\" but got ()");
		var index=Integer.parseInt(numStr.toString());
		return new CodeArg(type, index);
	}
	
	private CodeArg.Type readType(CharIterator data) throws MalformedJorthException{
		StringBuilder sb=new StringBuilder(CodeArg.Type.MAX_LEN);
		for(int i=0;i<CodeArg.Type.MAX_LEN;i++){
			char c=readOrUnexpected(data);
			if(!Character.isAlphabetic(c)){
				throw tokenNameFail(sb);
			}
			
			sb.append(Character.toLowerCase(c));
			if(sb.length()>=CodeArg.Type.MIN_LEN){
				for(var type : CodeArg.Type.values()){
					if(sb.indexOf(type.lower)==0) return type;
				}
			}
		}
		throw tokenNameFail(sb);
	}
	
	private char readOrUnexpected(CharIterator data) throws MalformedJorthException{
		if(!data.hasNext()) unexpectedEnd();
		return data.next();
	}
	
	private MalformedJorthException tokenNameFail(CharSequence sb) throws MalformedJorthException{
		throw new MalformedJorthException("Unknown # token \""+sb+"\"");
	}
	
	public JorthWriter write(String codeChunk, String... values) throws MalformedJorthException      {return write(seq(requireNonNull(codeChunk)), values);}
	public JorthWriter write(CharSequence codeChunk, String... values) throws MalformedJorthException{return write(seq(requireNonNull(codeChunk)), values);}
	public JorthWriter write(CharIterator codeChunk, String... values) throws MalformedJorthException{return write(inlineCodeValues(requireNonNull(codeChunk), values));}
	public JorthWriter write(String codeChunk) throws MalformedJorthException                        {return write(seq(requireNonNull(codeChunk)));}
	public JorthWriter write(CharSequence codeChunk) throws MalformedJorthException                  {return write(seq(requireNonNull(codeChunk)));}
	public JorthWriter write(CharIterator codeChunk) throws MalformedJorthException{
		requireNonNull(codeChunk);
		
		
		StringBuilder tokenBuffer=new StringBuilder();
		while(codeChunk.hasNext()){
			
			if(tokenBuffer.length()==2&&tokenBuffer.indexOf("//")==0){
				tokenBuffer.setLength(0);
				while(codeChunk.hasNext()){
					var c=codeChunk.next();
					if(c=='\n') break;
				}
				continue;
			}
			
			char c=codeChunk.next();
			
			if(c=='\n') line++;
			
			if(c=='\\'){
				var ch=readOrUnexpected(codeChunk);
				
				add(tokenBuffer, ch);
				continue;
			}
			if(Character.isWhitespace(c)){
				pushAndClear(tokenBuffer);
				continue;
			}
			
			if(tokenBuffer.isEmpty()){
				if(c=='\''){
					tokenBuffer.append('\'');
					transferStringContents(codeChunk, tokenBuffer);
					tokenBuffer.append('\'');
					
					pushAndClear(tokenBuffer);
					continue;
				}
			}
			add(tokenBuffer, c);
		}
		if(!tokenBuffer.isEmpty()){
			pushToken(tokenBuffer.toString());
		}
		
		return this;
	}
	private void pushAndClear(StringBuilder tokenBuffer) throws MalformedJorthException{
		if(!tokenBuffer.isEmpty()){
			pushToken(tokenBuffer.toString());
			tokenBuffer.setLength(0);
		}
	}
	private void add(StringBuilder tokenBuffer, char ch) throws MalformedJorthException{
		List<Character> specials=List.of('[', ']', '{', '}');
		
		char spec=0;
		
		for(char special : specials){
			if(ch==special){
				spec=special;
				break;
			}
		}
		if(spec!=0){
			pushAndClear(tokenBuffer);
			tokenBuffer.append(spec);
			pushAndClear(tokenBuffer);
			return;
		}
		tokenBuffer.append(ch);
	}
	
	private void pushToken(String tokenStr) throws MalformedJorthException{
		var override=overrides.get(tokenStr);
		if(override!=null){
			rawTokens.accept(this, new Token(line, override));
		}else{
			rawTokens.accept(this, new Token(line, tokenStr));
		}
	}
	
	private CharIterator.Sequence seq(CharSequence codeChunk){
		return new CharIterator.Sequence(codeChunk);
	}
	
	void addDefinition(String token, String value){
		overrides.put(token, value);
	}
	
	@Override
	public void close() throws MalformedJorthException{
	}
}
