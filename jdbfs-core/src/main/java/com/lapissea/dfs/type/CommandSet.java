package com.lapissea.dfs.type;

import com.lapissea.dfs.io.content.BBView;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.util.TextUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Consumer;

public final class CommandSet{
	
	public static class Builder{
		private sealed interface Cmd{
			void push(ContentOutputBuilder dest);
		}
		
		private record Skip(byte cmd, long bytes, int fields) implements Cmd{
			public Skip{
				if(cmd != SKIPB_UNKNOWN){
					var max = switch(cmd){
						case SKIPB_B -> 255;
						case SKIPB_I -> Integer.MAX_VALUE;
						case SKIPB_L -> Long.MAX_VALUE;
						default -> throw new IllegalStateException("Unexpected value: " + cmd);
					};
					checkRange(max, bytes);
				}
				checkRange(255, fields);
			}
			
			@Override
			public void push(ContentOutputBuilder dest){
				dest.write(cmd);
				dest.write(fields - 1);
				switch(cmd){
					case SKIPB_UNKNOWN -> { }
					case SKIPB_B -> dest.write((int)bytes);
					case SKIPB_I -> {
						byte[] bb = new byte[4];
						BBView.writeInt4(bb, 0, (int)bytes);
						dest.write(bb);
					}
					case SKIPB_L -> {
						byte[] bb = new byte[8];
						BBView.writeInt8(bb, 0, bytes);
						dest.write(bb);
					}
					default -> throw new IllegalStateException("Unexpected value: " + cmd);
				}
			}
		}
		
		private record Obj(byte cmd, boolean calcSize) implements Cmd{
			public Obj{
				if(!List.of(
					POTENTIAL_REF,
					DYNAMIC,
					CHPTR,
					REF_FIELD
				).contains(cmd)) throw new IllegalArgumentException(String.valueOf(cmd));
			}
			@Override
			public void push(ContentOutputBuilder dest){
				dest.write(cmd);
				dest.writeBoolean(calcSize);
			}
		}
		
		private record SkipFlow(int fieldOffset) implements Cmd{
			public SkipFlow{
				checkRange(31, fieldOffset);
			}
			@Override
			public void push(ContentOutputBuilder dest){
				dest.write(SKIPF_IF_NULL);
				dest.write(fieldOffset);
			}
		}
		
		private record EndFlow(byte cmd) implements Cmd{
			private EndFlow{
				if(!List.of(
					ENDF,
					UNMANAGED_REST
				).contains(cmd)) throw new IllegalArgumentException();
			}
			@Override
			public void push(ContentOutputBuilder dest){
				dest.write(cmd);
			}
		}
		
		private final List<Cmd> commands  = new ArrayList<>();
		private final List<Cmd> optionals = new ArrayList<>();
		private       boolean   done;
		
		public void endFlow(){
			requireNotDone();
			optionals.clear();
			
			if(!commands.isEmpty()){
				var last = commands.getLast();
				if(last instanceof Obj o){
					commands.set(commands.size() - 1, new Obj(o.cmd, false));
				}
			}
			
			addRequired(new EndFlow(ENDF));
			done = true;
		}
		public void unmanagedRest(){
			addRequired(new EndFlow(UNMANAGED_REST));
			done = true;
		}
		
		public void skipBytes8(int bytes){
			addOptional(new Skip(SKIPB_B, bytes, 1));
		}
		public void skipBytes32(int bytes){
			addOptional(new Skip(SKIPB_I, bytes, 1));
		}
		public void skipBytes64(long bytes){
			addOptional(new Skip(SKIPB_L, bytes, 1));
		}
		
		public void skipBytesUnknown(){
			addOptional(new Skip(SKIPB_UNKNOWN, -1, 1));
		}
		
		public void skipField(IOField<?, ?> field){
			requireNotDone();
			skipSize(field.getSizeDescriptor());
		}
		public void skipSize(SizeDescriptor<?> descriptor){
			requireNotDone();
			var sizO = descriptor.getFixed(WordSpace.BYTE);
			if(sizO.isPresent()){
				var siz = sizO.getAsLong();
				
				if(siz<=255) skipBytes8((int)siz);
				else if(siz<=Integer.MAX_VALUE) skipBytes32((int)siz);
				else skipBytes64(siz);
				
			}else{
				skipBytesUnknown();
			}
		}
		
		public void skipFlowIfNull(int offset){
			addRequired(new SkipFlow(offset));
		}
		
		public void potentialReference(){
			addRequired(new Obj(POTENTIAL_REF, true));
		}
		public void referenceField(){
			addRequired(new Obj(REF_FIELD, true));
		}
		
		public void dynamic(){
			addRequired(new Obj(DYNAMIC, true));
		}
		public void chptr(){
			addRequired(new Obj(CHPTR, true));
		}
		
		private void flushOptional(){
			if(optionals.isEmpty()) return;
			commands.addAll(optionals);
			optionals.clear();
		}
		
		private void addOptional(Cmd cmd){
			requireNotDone();
			
			//merge skips
			int        fields;
			BigInteger bigSum;
			int        lastIndex = optionals.size() - 1;
			
			if(lastIndex>=0 &&
			   optionals.get(lastIndex) instanceof Skip s1 && cmd instanceof Skip s2 &&
			   s1.cmd != SKIPB_UNKNOWN && s2.cmd != SKIPB_UNKNOWN &&
			   (fields = s1.fields + s2.fields)<=255 &&
			   (bigSum = BigInteger.valueOf(s1.bytes).add(BigInteger.valueOf(s2.bytes))).compareTo(BigInteger.valueOf(Long.MAX_VALUE))<=0
			){
				var sum = bigSum.longValue();
				
				if(sum<=255) optionals.set(lastIndex, new Skip(SKIPB_B, (int)bigSum.longValue(), fields));
				else if(sum<=Integer.MAX_VALUE) optionals.set(lastIndex, new Skip(SKIPB_I, (int)bigSum.longValue(), fields));
				else optionals.set(lastIndex, new Skip(SKIPB_L, sum, fields));
				return;
			}
			
			optionals.add(cmd);
		}
		
		private void addRequired(Cmd cmd){
			requireNotDone();
			flushOptional();
			commands.add(cmd);
		}
		
		private static void checkRange(long max, long value){
			if(value<0) throw new IllegalArgumentException("value must be positive");
			if(value>max) throw new IllegalArgumentException("value can not be larger than " + max);
		}
		private void requireNotDone(){
			if(done) throw new IllegalStateException("is done");
		}
		
		public CommandSet build(){
			if(!done) throw new IllegalStateException("not done");
			return toSet();
		}
		
		private CommandSet toSet(){
			var buff = new ContentOutputBuilder();
			for(Cmd command : commands){
				command.push(buff);
			}
			return new CommandSet(buff.toByteArray());
		}
		
		@Override
		public String toString(){
			return toSet().toString();
		}
	}
	
	public static CommandSet builder(Consumer<Builder> build){
		var b = builder();
		build.accept(b);
		return b.build();
	}
	public static Builder builder(){
		return new Builder();
	}
	
	public interface CmdReader{
		int cmd();
		int read8();
		int read32();
		long read64();
		boolean readBool();
	}
	
	public static final class RepeaterEnd extends BakedCmdReader{
		private long remainingRepeats;
		
		public RepeaterEnd(CommandSet repeatedSet, long repeats){
			super(repeatedSet.code);
			this.remainingRepeats = repeats;
		}
		
		@Override
		public int cmd(){
			int limit = code.length - 1;
			if(limit == pos){
				pos = 0;
				remainingRepeats--;
			}
			if(remainingRepeats == 0) return ENDF;
			
			return code[pos++];
		}
	}
	
	public static class BakedCmdReader implements CmdReader{
		protected final byte[] code;
		protected       int    pos;
		
		private BakedCmdReader(byte[] code){
			this.code = code;
		}
		
		@Override
		public int cmd(){
			return code[pos++];
		}
		
		@Override
		public int read8(){
			return code[pos++]&0xFF;
		}
		@Override
		public int read32(){
			var p = pos;
			pos += 4;
			return BBView.readInt4(code, p);
		}
		@Override
		public long read64(){
			var p = pos;
			pos += 8;
			return BBView.readInt8(code, p);
		}
		@Override
		public boolean readBool(){
			return code[pos++] == 1;
		}
		
		@Override
		public String toString(){
			if(pos>=code.length) return "CmdReader{}";
			
			var    reader = new BakedCmdReader(code);
			String result;
			int    rPos;
			do{
				rPos = reader.pos;
				result = reader.readCmdStr();
				if(result == null){
					result = "<Error>";
					break;
				}
			}while(reader.pos<=pos);
			
			return "CmdReader{" + (rPos == pos? "next" : "current") + ": " + result + "}";
		}
		
		private String readCmdStr(){
			var oldPos = pos;
			try{
				var cmd = cmd();
				return switch(cmd){
					case ENDF -> "ENDF";
					case UNMANAGED_REST -> "UNMANAGED_REST";
					case SKIPB_B -> props("SKIPB_B", read8(), "jump", read8(), "byte");
					case SKIPB_I -> props("SKIPB_I", read8(), "jump", read32(), "byte");
					case SKIPB_L -> props("SKIPB_L", read8(), "jump", read64(), "byte");
					case SKIPB_UNKNOWN -> props("SKIPB_UNKNOWN", read8(), "field");
					case SKIPF_IF_NULL -> "SKIPF_IF_NULL(" + read8() + " offset)";
					case POTENTIAL_REF -> obj("POTENTIAL_REF");
					case DYNAMIC -> obj("DYNAMIC");
					case CHPTR -> obj("CHPTR");
					case REF_FIELD -> obj("REF_FIELD");
					default -> throw new IllegalStateException("Unexpected value: " + cmd);
				};
			}catch(Throwable e){
				pos = oldPos;
				return null;
			}
		}
		
		private static String props(String name, Object... props){
			StringBuilder b = new StringBuilder();
			b.append(name);
			boolean any = false;
			for(int i = 0; i<props.length; i += 2){
				long val = ((Number)props[i]).longValue();
				if(val == 0) continue;
				if(!any){
					any = true;
					b.append('(');
				}else b.append(", ");
				b.append(val).append(' ').append(TextUtil.plural((String)props[i + 1], (int)Math.min(100, val)));
			}
			if(any) b.append(')');
			return b.toString();
		}
		private static String iprop(long val, String name){
			return val == 0? name : val + " " + name;
		}
		private String obj(String name){
			if(readBool()) return name;
			return name + "(No advance)";
		}
		
	}
	
	public static final byte ENDF           = 100;
	public static final byte UNMANAGED_REST = 100 + 1;
	public static final byte SKIPB_B        = 100 + 2;
	public static final byte SKIPB_I        = 100 + 3;
	public static final byte SKIPB_L        = 100 + 4;
	public static final byte SKIPB_UNKNOWN  = 100 + 5;
	public static final byte SKIPF_IF_NULL  = 100 + 6;
	public static final byte POTENTIAL_REF  = 100 + 7;
	public static final byte DYNAMIC        = 100 + 8;
	public static final byte CHPTR          = 100 + 9;
	public static final byte REF_FIELD      = 100 + 10;
	
	
	private final byte[] code;
	
	private CommandSet(byte[] code){
		this.code = code;
	}
	
	public CmdReader reader(){
		return new BakedCmdReader(code);
	}
	
	@Override
	public String toString(){
		var str = new StringJoiner(", ", "[", "]");
		
		var reader = new BakedCmdReader(code);
		
		while(reader.pos<code.length){
			try{
				var cmd = reader.readCmdStr();
				if(cmd == null) throw new Throwable();
				str.add(cmd);
			}catch(Throwable e){
				return str.add("<Incomplete> ...").toString();
			}
		}
		
		return str.toString();
	}
}
