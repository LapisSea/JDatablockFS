package com.lapissea.jorth.old.lang;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class Token{
	
	public final int    line;
	public final String source;
	
	public Token(int line, String source){
		this.line = line;
		this.source = source;
	}
	public String getSource(){
		return source;
	}
	
	public boolean isFloating(){
		try{
			Double.parseDouble(source);
			return true;
		}catch(NumberFormatException e){
			return false;
		}
	}
	
	public boolean isNumber(){
		return isInteger() || isFloating();
	}
	
	public boolean isInteger(){
		try{
			Long.parseLong(source);
			return true;
		}catch(NumberFormatException e){
			return false;
		}
	}
	
	public boolean isStringLiteral(){
		return source.startsWith("'") && source.endsWith("'");
	}
	public String getStringLiteralValue(){
		assert isStringLiteral();
		return source.substring(1, source.length() - 1);
	}
	
	public String lower(){
		return source.toLowerCase();
	}
	
	
	public Visibility asVisibility() throws MalformedJorthException{
		return Visibility.fromName(source);
	}
	
	@Override
	public String toString(){
		return source;
	}
	
	public interface Sequence{
		
		private static MalformedJorthException fail() throws MalformedJorthException{
			throw new MalformedJorthException("Unexpected end of tokens");
		}
		
		Sized EMPTY = new Sized(){
			@Override
			public Token pop() throws MalformedJorthException{
				throw fail();
			}
			@Override
			public Token peek() throws MalformedJorthException{
				throw fail();
			}
			@Override
			public Sequence clone(){
				return this;
			}
			@Override
			public int getRemaining(){
				return 0;
			}
			@Override
			public String toString(){
				return "";
			}
		};
		
		static Sized of(){
			return EMPTY;
		}
		
		static Sized of(Collection<Token> token){
			return new Writable(token);
		}
		
		static Sequence of(Token token){
			Objects.requireNonNull(token);
			return new Sized(){
				private boolean read = false;
				@Override
				public Token pop() throws MalformedJorthException{
					if(read) fail();
					read = true;
					return token;
				}
				@Override
				public Token peek() throws MalformedJorthException{
					if(read) fail();
					return token;
				}
				@Override
				public Sequence clone(){
					return read? EMPTY : of(token);
				}
				@Override
				public int getRemaining(){
					return read? 0 : 1;
				}
				@Override
				public String toString(){
					return read? "" : "L" + token.line + " - " + token.source;
				}
			};
		}
		abstract class Sized implements Sequence{
			public abstract int getRemaining();
			
			@Override
			public boolean isEmpty(){
				return getRemaining() == 0;
			}
			@Override
			public void requireCount(int count) throws MalformedJorthException{
				if(getRemaining()<count) throw new MalformedJorthException("token requires at least " + count + " words!");
			}
			@Override
			public abstract String toString();
			@Override
			public abstract Sequence clone();
		}
		
		final class Writable extends Sized{
			private final List<Token> buffer = new ArrayList<>();
			
			public Writable(){ }
			public Writable(Collection<Token> initial){
				buffer.addAll(initial);
				for(Token token : buffer){
					if(token == null) throw new NullPointerException();
				}
			}
			private Writable(List<Token> initial, int ignore){
				buffer.addAll(initial);
			}
			
			public void write(Token token){
				buffer.add(token);
			}
			@Override
			public Token pop() throws MalformedJorthException{
				if(isEmpty()) throw new MalformedJorthException("Unexpected end of tokens");
				return buffer.remove(buffer.size() - 1);
			}
			@Override
			public Token peek() throws MalformedJorthException{
				if(isEmpty()) throw new MalformedJorthException("Unexpected end of tokens");
				return buffer.get(buffer.size() - 1);
			}
			@Override
			public Sequence clone(){
				return new Writable(buffer, 0);
			}
			@Override
			public int getRemaining(){
				return buffer.size();
			}
			@Override
			public boolean isEmpty(){
				return buffer.isEmpty();
			}
			@Override
			public void requireCount(int count) throws MalformedJorthException{
				if(buffer.size()<count) throw new MalformedJorthException("token requires at least " + count + " words!");
			}
			@Override
			public String toString(){
				if(buffer.isEmpty()) return "";
				
				var sb        = new StringBuilder();
				int firstLine = -1;
				int lastLine  = -1;
				for(Token token : buffer){
					if(firstLine == -1) firstLine = token.line;
					if(token.line != lastLine){
						sb.append("\nL").append(token.line).append(" -");
						lastLine = token.line;
					}
					sb.append(' ').append(token.source);
				}
				if(firstLine == lastLine){
					sb.setCharAt(0, '\u200B');
				}
				return sb.toString();
			}
		}
		
		void requireCount(int count) throws MalformedJorthException;
		boolean isEmpty();
		
		Token pop() throws MalformedJorthException;
		Token peek() throws MalformedJorthException;
		Sequence clone();
		
		default Stream<Token> cloneTokens(){
			return clone().parseStream(Sequence::pop);
		}
		
		default <T> void parseAll(Consumer<T> dest, UnsafeFunction<Sequence, T, MalformedJorthException> parser) throws MalformedJorthException{
			while(!isEmpty()){
				dest.accept(parser.apply(this));
			}
		}
		
		default <T> Stream<T> parseStream(UnsafeFunction<Sequence, T, MalformedJorthException> parser){
			return Stream.generate(() -> {
				if(isEmpty()) return null;
				try{
					return Objects.requireNonNull(parser.apply(this));
				}catch(MalformedJorthException e){
					throw UtilL.uncheckedThrow(e);
				}
			}).takeWhile(Objects::nonNull);
		}
		default <T> List<T> parseAll(UnsafeFunction<Sequence, T, MalformedJorthException> parser) throws MalformedJorthException{
			var result = new ArrayList<T>();
			parseAll(result::add, parser);
			return result;
		}
	}
}
