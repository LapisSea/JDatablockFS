package com.lapissea.jorth.lang;

import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.EndOfCode;
import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.type.Access;
import com.lapissea.jorth.lang.type.KeyedEnum;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.UtilL;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import static java.lang.Float.parseFloat;
import static java.lang.Integer.parseInt;

public class Tokenizer implements CodeStream, TokenSource{
	
	private static final boolean SYNCHRONOUS_SAFETY =
		UtilL.sysProperty("jorth.synchronousSafety").map(Boolean::valueOf).orElse(false);
	
	private static final BitSet SPECIALS = new BitSet();
	
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
	
	static{
		"[]{}',;/<>@".chars().forEach(SPECIALS::set);
	}
	
	private enum WorkerState{
		WAIT, WAIT_HOT,
		READ,
		END, END_CONFIRM
	}
	
	private final CodeDestination dad;
	
	private final Deque<CharSequence> codeBuffer = new ArrayDeque<>();
	
	private CharSequence code;
	private int          pos;
	private int          line;
	
	private TokenSource transformed;
	
	private Throwable   workerError;
	private WorkerState workerState = WorkerState.WAIT;
	
	public Tokenizer(CodeDestination dad, int line){
		this(dad, line, Thread.ofVirtual().name("token consumer")::start);
	}
	public Tokenizer(CodeDestination dad, int line, Executor executor){
		this.dad = dad;
		this.line = line;
		executor.execute(this::workerLoop);
	}
	
	private void workerLoop(){
		try{
			while(workerState != WorkerState.END || hasMore()){
				workerExec();
			}
			setWorkerState(WorkerState.END_CONFIRM);
		}catch(Throwable e){
			workerError = e;
		}
	}
	
	private void workerExec() throws MalformedJorth{
		switch(workerState){
			case WAIT -> waitForStateChange();
			case WAIT_HOT -> {
				if(waitHot()) return;
				synchronized(this){
					if(workerState == WorkerState.WAIT_HOT){
						setWorkerState(WorkerState.WAIT);
					}
				}
			}
			case READ -> {
				synchronized(this){
					setWorkerState(WorkerState.WAIT_HOT);
					if(code == null){
						this.code = codeBuffer.removeFirst();
						pos = 0;
						skipWhitespace();
						if(code == null){
							if(hasMore()){
								setWorkerState(WorkerState.READ);
							}
							return;
						}
					}
				}
				
				parseExisting();
			}
			case END, END_CONFIRM -> {
				//finish up
				parseExisting();
			}
		}
		
	}
	private void parseExisting() throws MalformedJorth{
		while(hasMore()){
			dad.parse(transformed);
		}
	}
	private void setWorkerState(WorkerState workerState){
		synchronized(this){
			this.workerState = workerState;
			notifyAll();
		}
	}
	
	private void waitForStateChange(){
		synchronized(this){
			try{
				wait(2);
			}catch(InterruptedException e){
				throw new RuntimeException(e);
			}
		}
	}
	
	private boolean waitHot(){
		for(int i = 0; i<1000; i++){
			Thread.yield();
			if(workerState != WorkerState.WAIT_HOT) return true;
			Thread.onSpinWait();
		}
		synchronized(this){
			if(workerState == WorkerState.WAIT_HOT){
				workerState = WorkerState.WAIT;
			}
		}
		return false;
	}
	
	private boolean workerEnding(){
		return workerState == WorkerState.END || workerState == WorkerState.END_CONFIRM;
	}
	
	@Override
	public boolean hasMore(){
		return code != null || peeked != null || !codeBuffer.isEmpty() || !workerEnding();
	}
	
	@Override
	public CodeStream write(CharSequence code) throws MalformedJorth{
		if(transformed == null) transformed = dad.transform(this);
		synchronized(this){
			writeError("<previous>");
			codeBuffer.addLast(code);
			setWorkerState(WorkerState.READ);
		}
		if(SYNCHRONOUS_SAFETY){
			while(workerState != WorkerState.WAIT && workerState != WorkerState.WAIT_HOT){
				UtilL.sleep(1);
				writeError(code);
			}
			writeError(code);
		}
		return this;
	}
	private void writeError(CharSequence code){
		try{
			checkWorkerError();
		}catch(Throwable e){
			throw new RuntimeException("\n" + code, e);
		}
	}
	
	private synchronized void skipWhitespace(){
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
	public Token peekToken() throws MalformedJorth{
		if(peeked == null) peeked = readToken();
		return peeked;
	}
	
	@Override
	public Token readToken() throws MalformedJorth{
		if(peeked != null){
			var p = peeked;
			peeked = null;
			return p;
		}
		
		while(code == null){
			synchronized(this){
				if(!codeBuffer.isEmpty()){
					code = codeBuffer.removeFirst();
					pos = 0;
					line++;
					skipWhitespace();
					continue;
				}else if(workerState == WorkerState.READ){
					setWorkerState(WorkerState.WAIT_HOT);
				}
				
				if(workerEnding() && !hasMore()){
					throw new EndOfCode();
				}
			}
			
			
			if(waitHot()) continue;
			waitForStateChange();
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
		
		
		if(word.equals("null")) return new Token.Null(lastLine);
		if(word.equals("true")) return new Token.Bool(lastLine, true);
		if(word.equals("false")) return new Token.Bool(lastLine, false);
		
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
	public void close() throws MalformedJorth{
		setWorkerState(WorkerState.END);
		while(workerState != WorkerState.END_CONFIRM){
			checkWorkerError();
			waitForStateChange();
		}
	}
	
	private final Set<Thread> alreadyThrown = Collections.synchronizedSet(new HashSet<>());
	
	private void checkWorkerError(){
		if(workerError != null){
			if(!alreadyThrown.add(Thread.currentThread())){
				throw new IllegalStateException("Worker failed");
			}
			throw UtilL.uncheckedThrow(workerError);
		}
	}
}
