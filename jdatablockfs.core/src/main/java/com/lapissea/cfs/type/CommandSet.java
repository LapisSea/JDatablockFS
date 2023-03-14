package com.lapissea.cfs.type;

import com.lapissea.cfs.internal.MemPrimitive;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
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
				if(cmd != SKIPB_UNKOWN){
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
					case SKIPB_UNKOWN -> { }
					case SKIPB_B -> dest.write((int)bytes);
					case SKIPB_I -> {
						byte[] bb = new byte[4];
						MemPrimitive.setInt(bb, 0, (int)bytes);
						dest.write(bb);
					}
					case SKIPB_L -> {
						byte[] bb = new byte[8];
						MemPrimitive.setLong(bb, 0, bytes);
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
				).contains(cmd)) throw new IllegalArgumentException(cmd + "");
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
				var last = commands.get(commands.size() - 1);
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
			addOptional(new Skip(SKIPB_UNKOWN, -1, 1));
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
			   s1.cmd != SKIPB_UNKOWN && s2.cmd != SKIPB_UNKOWN &&
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
			return MemPrimitive.getInt(code, p);
		}
		@Override
		public long read64(){
			var p = pos;
			pos += 8;
			return MemPrimitive.getLong(code, p);
		}
		@Override
		public boolean readBool(){
			return code[pos++] == 1;
		}
		
		@Override
		public String toString(){
			var res = new StringJoiner(", ", "CmdReader{", "}");
			if(pos>=code.length) return res.add("END").toString();
			var cmd = code[pos];
			return res.add("Next: " + switch(cmd){
				case ENDF -> "ENDF";
				case UNMANAGED_REST -> "UNMANAGED_REST";
				case SKIPB_B -> "SKIPB_B";
				case SKIPB_I -> "SKIPB_I";
				case SKIPB_L -> "SKIPB_L";
				case SKIPB_UNKOWN -> "SKIPB_UNKOWN";
				case SKIPF_IF_NULL -> "SKIPF_IF_NULL";
				case POTENTIAL_REF -> "POTENTIAL_REF";
				case DYNAMIC -> "DYNAMIC";
				case CHPTR -> "CHPTR";
				case REF_FIELD -> "REF_FIELD";
				default -> "?? " + cmd;
			}).toString();
		}
	}
	
	public static final byte ENDF           = 0;
	public static final byte UNMANAGED_REST = 1;
	public static final byte SKIPB_B        = 2;
	public static final byte SKIPB_I        = 3;
	public static final byte SKIPB_L        = 4;
	public static final byte SKIPB_UNKOWN   = 5;
	public static final byte SKIPF_IF_NULL  = 6;
	public static final byte POTENTIAL_REF  = 7;
	public static final byte DYNAMIC        = 8;
	public static final byte CHPTR          = 9;
	public static final byte REF_FIELD      = 10;
	
	
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
		
		var reader = reader();
		
		while(true){
			try{
				var cmd = reader.cmd();
				str.add(switch(cmd){
					case ENDF -> "ENDF";
					case UNMANAGED_REST -> "UNMANAGED_REST";
					case SKIPB_B -> props("SKIPB_B", reader.read8(), "jump", reader.read8(), "byte");
					case SKIPB_I -> props("SKIPB_B", reader.read8(), "jump", reader.read32(), "byte");
					case SKIPB_L -> props("SKIPB_B", reader.read8(), "jump", reader.read64(), "byte");
					case SKIPB_UNKOWN -> props("SKIPB_UNKOWN", reader.read8(), "field");
					case SKIPF_IF_NULL -> "SKIPF_IF_NULL(" + reader.read8() + " offset)";
					case POTENTIAL_REF -> obj(reader, "POTENTIAL_REF");
					case DYNAMIC -> obj(reader, "DYNAMIC");
					case CHPTR -> obj(reader, "CHPTR");
					case REF_FIELD -> obj(reader, "REF_FIELD");
					default -> throw new IllegalStateException("Unexpected value: " + cmd);
				});
				if(cmd == ENDF || cmd == UNMANAGED_REST) return str.toString();
			}catch(Throwable e){
				return str.add("<Incomplete> ...").toString();
			}
		}
	}
	
	private String props(String name, Object... props){
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
	private String iprop(long val, String name){
		return val == 0? name : val + " " + name;
	}
	private String obj(CmdReader reader, String name){
		if(reader.readBool()) return name;
		return name + "(No advance)";
	}
}
