package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.exceptions.IllegalBitValueException;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.Offset;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.io.struct.VariableNode.Flag.FlagBlock;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.*;

public class BitBlockNode extends VariableNode.FixedSize.Node<Object>{
	
	private static Stream<Flag<?>> getFlags(FlagBlock blockInfo, IOStruct type){
		return blockInfo.range().mapToObj(type.getVariables()::get).map(n->(Flag<?>)n);
	}
	
	private final          FlagBlock     blockInfo;
	private final          IOStruct      type;
	public final transient List<Flag<?>> flagsNodes;
	
	public BitBlockNode(IOStruct type, int flagIndex){
		this(type, ((Flag<?>)type.getVariables().get(flagIndex)).getBlockInfo());
	}
	private BitBlockNode(IOStruct type, FlagBlock blockInfo){
		super(new VarInfo(getFlags(blockInfo, type).map(v->v.info.name()).collect(Collectors.joining(", ", "Flags[", "]")), Integer.MIN_VALUE/2), blockInfo.wordSize().bytes);
		this.type=type;
		this.blockInfo=blockInfo;
		flagsNodes=getFlags(blockInfo, type).collect(Collectors.toUnmodifiableList());
	}
	
	@Override
	public Object getValue(IOInstance source){ throw new UnsupportedOperationException();}
	@Override
	public void setValue(IOInstance target, Object newValue){throw new UnsupportedOperationException();}
	
	
	@Override
	public Object read(IOInstance target, ContentReader source, Object oldVal, Cluster cluster) throws IOException{
		read(target, cluster, source);
		return null;
	}
	@Override
	public void read(IOInstance target, Cluster cluster, ContentReader source) throws IOException{
		classCheck(target);
		
		NumberSize size=blockInfo.wordSize();
		long       bits=size.read(source);
		
		try(var flags=new FlagReader(bits, size)){
			for(var flagNode : flagsNodes){
				if(DEBUG_VALIDATION){
					int remaining=flags.remainingCount();
					
					flagNode.read(target, flags);
					
					int actuallyRead=remaining-flags.remainingCount();
					if(actuallyRead!=flagNode.getTotalBits()) throw new IOException("Failed to read correct bit count "+actuallyRead+"/"+flagNode.getTotalBits());
				}else{
					flagNode.read(target, flags);
				}
			}
		}catch(IllegalBitValueException e){
			StringBuilder sb=new StringBuilder("Padding bits invalid in ").append(this).append("\n");
			
			Map<Character, String> charMap=new LinkedHashMap<>();
			
			for(int i=0;i<flagsNodes.size();i++){
				Flag<?> flagNode=flagsNodes.get(i);
				
				char c=flagNode.info.name().charAt(0);
				if(charMap.containsKey(c)) c=Character.isUpperCase(c)?Character.toLowerCase(c):Character.toUpperCase(c);
				if(charMap.containsKey(c)) c=Integer.toString(i).charAt(0);
				
				assert !charMap.containsKey(c);
				
				charMap.put(c, flagNode.info.name());
			}
			
			sb.append(charMap).append("\n");
			
			for(Flag<?> flagsNode : flagsNodes){
				for(int i=0;i<flagsNode.getBitSize();i++){
					sb.append(charMap.entrySet().stream().filter(e1->e1.getValue().equals(flagsNode.info.name())).findAny().orElseThrow().getKey());
				}
				sb.append("-".repeat(flagsNode.getPaddingBits()));
			}
			
			sb.append("\n");
			sb.append(new StringBuilder(size.binaryString(bits)).reverse());
			sb.append("\n");
			
			try(var flags=new FlagReader(bits, size)){
				for(Flag<?> flagsNode : flagsNodes){
					for(int i=0;i<flagsNode.getBitSize();i++){
						sb.append(' ');
						flags.readBoolBit();
					}
					
					for(int i=0;i<flagsNode.getPaddingBits();i++){
						sb.append(flags.readBoolBit()?' ':'!');
					}
				}
			}
			
			throw new IllegalBitValueException(sb.toString(), e);
		}
	}
	
	@Override
	public void write(IOInstance target, Cluster cluster, ContentWriter dest, Object source) throws IOException{
		write(target, cluster, dest);
	}
	
	@Override
	public void write(IOInstance target, Cluster cluster, ContentWriter dest) throws IOException{
		classCheck(target);
		
		try(var flags=new FlagWriter.AutoPop(blockInfo.wordSize(), dest)){
			for(var flagNode : flagsNodes){
				if(DEBUG_VALIDATION){
					int remaining=flags.remainingCount();
					
					flagNode.write(target, flags);
					
					int actuallyWrote=remaining-flags.remainingCount();
					if(actuallyWrote!=flagNode.getTotalBits()) throw new IOException("Failed to write correct bit count "+actuallyWrote+"/"+flagNode.getTotalBits());
				}else{
					flagNode.write(target, flags);
				}
			}
			
		}
	}
	
	private void classCheck(IOInstance target){
		if(target.getStruct()!=type) throw new ClassCastException(TextUtil.toString(target.getStruct(), "!=", type));
	}
	
	
	@Override
	public void applyKnownOffset(Offset offset){
		super.applyKnownOffset(offset);
		
		Offset subOff=offset;
		for(var flagNode : flagsNodes){
			flagNode.applyKnownOffset(subOff);
			subOff=subOff.add(Offset.fromBits(flagNode.getTotalBits()));
		}
	}
	
	
	public FlagBlock blockInfo(){
		return blockInfo;
	}
	
}
