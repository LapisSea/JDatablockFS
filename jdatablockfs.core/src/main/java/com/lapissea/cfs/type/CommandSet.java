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
		private interface Cmd{
			void push(Builder builder);
		}
		
		private record Skip(byte cmd, long bytes, int fields) implements Cmd{
			@Override
			public void push(Builder builder){
				builder.cmdId(cmd);
				builder.write8(fields-1);
				switch(cmd){
					case SKIPB_UNKOWN -> {}
					case SKIPB_B -> builder.write8((int)bytes);
					case SKIPB_I -> {
						byte[] bb=new byte[4];
						MemPrimitive.setInt(bb, 0, (int)bytes);
						builder.data.write(bb);
					}
					case SKIPB_L -> {
						byte[] bb=new byte[8];
						MemPrimitive.setLong(bb, 0, bytes);
						builder.data.write(bb);
					}
					default -> throw new IllegalStateException("Unexpected value: "+cmd);
				}
				
			}
		}
		
		private final ContentOutputBuilder data     =new ContentOutputBuilder();
		private final List<Cmd>            optionals=new ArrayList<>();
		private       boolean              done;
		
		public void endFlow(){
			requireNotDone();
			optionals.clear();
			cmdId(ENDF);
			done=true;
		}
		public void unmanagedRest(){
			requireNotDone();
			flushOptional();
			cmdId(UNMANAGED_REST);
			done=true;
		}
		
		public void skipBytes8(int bytes){
			requireNotDone();
			optionals.add(new Skip(SKIPB_B, bytes, 1));
		}
		public void skipBytes32(int bytes){
			requireNotDone();
			optionals.add(new Skip(SKIPB_I, bytes, 1));
		}
		public void skipBytes64(long bytes){
			requireNotDone();
			optionals.add(new Skip(SKIPB_L, bytes, 1));
		}
		
		public void skipBytesUnknown(){
			requireNotDone();
			optionals.add(new Skip(SKIPB_UNKOWN, -1, 1));
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
			flushOptional();
			cmdId(SKIPF_IF_NULL);
			checkRange(63, offset);
			data.write(offset);
		}
		
		public void potentialReference(){
			flushOptional();
			cmdId(POTENTIAL_REF);
		}
		
		public void dynamic(){
			flushOptional();
			cmdId(DYNAMIC);
		}
		public void chptr(){
			flushOptional();
			cmdId(CHPTR);
		}
		
		private void write8(int value){
			checkRange(255, value);
			data.write(value);
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
			
			for(Cmd cmd : optionals){
				cmd.push(this);
			}
			optionals.clear();
		}
		
		private void cmdId(byte id){
			requireNotDone();
			data.write(id);
		}
		
		private void checkRange(long max, long value){
			if(value<0) throw new IllegalArgumentException("value must be positive");
			if(value>max) throw new IllegalArgumentException("value can not be larger than "+max);
		}
		private void requireNotDone(){
			if(done) throw new IllegalStateException("is done");
		}
		
		public CommandSet build(){
			if(!done) throw new IllegalStateException("not done");
			return new CommandSet(data.toByteArray());
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
	}
	
	public static final class RepeaterEnd implements CmdReader{
		private final byte[] code;
		private       int    pos;
		private       long   remainingRepeats;
		
		public RepeaterEnd(CommandSet repeatedSet, long repeats){
			code=repeatedSet.code;
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
	}
	
	public static final class BakedCmdReader implements CmdReader{
		private final byte[] code;
		private       int    pos;
		
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
				case SKIPB_B -> "SKIPB_B("+reader.read8()+" fields, "+reader.read8()+" bytes)";
				case SKIPB_I -> "SKIPB_I("+reader.read8()+" fields, "+reader.read32()+" bytes)";
				case SKIPB_L -> "SKIPB_L("+reader.read8()+" fields, "+reader.read64()+" bytes)";
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
