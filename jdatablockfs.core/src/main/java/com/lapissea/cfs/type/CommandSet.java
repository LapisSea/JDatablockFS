package com.lapissea.cfs.type;

import com.lapissea.cfs.internal.MemPrimitive;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;

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
				if(cmd!=SKIPB_UNKOWN){
					var max=switch(cmd){
						case SKIPB_B -> 255;
						case SKIPB_I -> Integer.MAX_VALUE;
						case SKIPB_L -> Long.MAX_VALUE;
						default -> throw new IllegalStateException("Unexpected value: "+cmd);
					};
					checkRange(max, bytes);
				}
				checkRange(255, fields);
			}
			
			@Override
			public void push(ContentOutputBuilder dest){
				dest.write(cmd);
				dest.write(fields-1);
				switch(cmd){
					case SKIPB_UNKOWN -> {}
					case SKIPB_B -> dest.write((int)bytes);
					case SKIPB_I -> {
						byte[] bb=new byte[4];
						MemPrimitive.setInt(bb, 0, (int)bytes);
						dest.write(bb);
					}
					case SKIPB_L -> {
						byte[] bb=new byte[8];
						MemPrimitive.setLong(bb, 0, bytes);
						dest.write(bb);
					}
					default -> throw new IllegalStateException("Unexpected value: "+cmd);
				}
				
			}
		}
		
		private record Obj(byte cmd, boolean calcSize) implements Cmd{
			public Obj{
				if(!List.of(
					POTENTIAL_REF,
					DYNAMIC,
					CHPTR
				).contains(cmd)) throw new IllegalArgumentException(cmd+"");
			}
			@Override
			public void push(ContentOutputBuilder dest){
				dest.write(cmd);
				dest.writeBoolean(calcSize);
			}
		}
		
		private record SkipFlow(int fieldOffset) implements Cmd{
			public SkipFlow{
				checkRange(63, fieldOffset);
			}
			@Override
			public void push(ContentOutputBuilder dest){
				dest.write(SKIPF_IF_NULL);
				dest.write(fieldOffset);
			}
		}
		
		private record EndFlow(byte cmd) implements Cmd{
			private EndFlow{
				if(!List.of(ENDF, UNMANAGED_REST).contains(cmd)) throw new IllegalArgumentException();
			}
			@Override
			public void push(ContentOutputBuilder dest){
				dest.write(cmd);
			}
		}
		
		private final List<Cmd> commands =new ArrayList<>();
		private final List<Cmd> optionals=new ArrayList<>();
		private       boolean   done;
		
		public void endFlow(){
			requireNotDone();
			optionals.clear();
			
			if(!commands.isEmpty()){
				var last=commands.get(commands.size()-1);
				switch(last){
					case Obj o -> commands.set(commands.size()-1, new Obj(o.cmd, false));
					default -> {}
				}
			}
			
			addRequired(new EndFlow(ENDF));
			done=true;
		}
		public void unmanagedRest(){
			addRequired(new EndFlow(UNMANAGED_REST));
			done=true;
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
			var sizO=descriptor.getFixed(WordSpace.BYTE);
			if(sizO.isPresent()){
				var siz=sizO.getAsLong();
				
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
		
		public void dynamic(){
			addRequired(new Obj(DYNAMIC, true));
		}
		public void chptr(){
			addRequired(new Obj(CHPTR, true));
		}
		
		private void flushOptional(){
			if(optionals.isEmpty()) return;
			
			//merge skips
			for(int i=0;i<optionals.size()-1;i++){
				var c1=optionals.get(i);
				var c2=optionals.get(i+1);
				
				if(!(c1 instanceof Skip s1)||!(c2 instanceof Skip s2)) continue;
				if(s1.cmd==SKIPB_UNKOWN||s2.cmd==SKIPB_UNKOWN) continue;
				
				var fields=s1.fields+s2.fields;
				if(fields>255) continue;
				
				var sum=BigInteger.valueOf(s1.bytes).add(BigInteger.valueOf(s2.bytes));
				
				var ok=true;
				if(sum.compareTo(BigInteger.valueOf(255))<=0) optionals.set(i, new Skip(SKIPB_B, (int)sum.longValue(), fields));
				else if(sum.compareTo(BigInteger.valueOf(Integer.MAX_VALUE))<=0) optionals.set(i, new Skip(SKIPB_I, (int)sum.longValue(), fields));
				else if(sum.compareTo(BigInteger.valueOf(Long.MAX_VALUE))<=0) optionals.set(i, new Skip(SKIPB_L, sum.longValue(), fields));
				else ok=false;
				
				if(ok){
					optionals.remove(i+1);
					i--;
				}
			}
			
			commands.addAll(optionals);
			optionals.clear();
		}
		
		private void addOptional(Cmd cmd){
			requireNotDone();
			optionals.add(cmd);
		}
		
		private void addRequired(Cmd cmd){
			requireNotDone();
			flushOptional();
			commands.add(cmd);
		}
		
		private static void checkRange(long max, long value){
			if(value<0) throw new IllegalArgumentException("value must be positive");
			if(value>max) throw new IllegalArgumentException("value can not be larger than "+max);
		}
		private void requireNotDone(){
			if(done) throw new IllegalStateException("is done");
		}
		
		public CommandSet build(){
			if(!done) throw new IllegalStateException("not done");
			
			var buff=new ContentOutputBuilder();
			for(Cmd command : commands){
				command.push(buff);
			}
			return new CommandSet(buff.toByteArray());
		}
	}
	
	public static CommandSet builder(Consumer<Builder> build){
		var b=builder();
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
			this.remainingRepeats=repeats;
		}
		
		@Override
		public int cmd(){
			int limit=code.length-1;
			if(limit==pos){
				pos=0;
				remainingRepeats--;
			}
			if(remainingRepeats==0) return ENDF;
			
			return code[pos++];
		}
	}
	
	public static class BakedCmdReader implements CmdReader{
		protected final byte[] code;
		protected       int    pos;
		
		private BakedCmdReader(byte[] code){
			this.code=code;
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
			var p=pos;
			pos+=4;
			return MemPrimitive.getInt(code, p);
		}
		@Override
		public long read64(){
			var p=pos;
			pos+=8;
			return MemPrimitive.getLong(code, p);
		}
		@Override
		public boolean readBool(){
			return code[pos++]==1;
		}
	}
	
	public static final byte ENDF          =0;
	public static final byte UNMANAGED_REST=1;
	public static final byte SKIPB_B       =2;
	public static final byte SKIPB_I       =3;
	public static final byte SKIPB_L       =4;
	public static final byte SKIPB_UNKOWN  =5;
	public static final byte SKIPF_IF_NULL =6;
	public static final byte POTENTIAL_REF =7;
	public static final byte DYNAMIC       =8;
	public static final byte CHPTR         =9;
	
	
	private final byte[] code;
	
	private CommandSet(byte[] code){
		this.code=code;
	}
	
	public CmdReader reader(){
		return new BakedCmdReader(code);
	}
	
	@Override
	public String toString(){
		StringJoiner str=new StringJoiner(", ", "[", "]");
		
		var reader=reader();
		
		while(true){
			var cmd=reader.cmd();
			
			str.add(switch(cmd){
				case ENDF -> "ENDF";
				case UNMANAGED_REST -> "UNMANAGED_REST";
				case SKIPB_B -> "SKIPB_B("+reader.read8()+" jump, "+reader.read8()+" bytes)";
				case SKIPB_I -> "SKIPB_I("+reader.read8()+" jump, "+reader.read32()+" bytes)";
				case SKIPB_L -> "SKIPB_L("+reader.read8()+" jump, "+reader.read64()+" bytes)";
				case SKIPB_UNKOWN -> "SKIPB_UNKOWN("+reader.read8()+" fields)";
				case SKIPF_IF_NULL -> "SKIPF_IF_NULL("+reader.read8()+" offset)";
				case POTENTIAL_REF -> "POTENTIAL_REF";
				case DYNAMIC -> "DYNAMIC";
				case CHPTR -> "CHPTR";
				default -> throw new IllegalStateException("Unexpected value: "+cmd);
			});
			
			if(cmd==ENDF||cmd==UNMANAGED_REST) return str.toString();
		}
	}
}
