package com.lapissea.cfs.type;

import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.VirtualAccessor;

import java.util.OptionalLong;
import java.util.function.Function;

public class IOInstance<SELF extends IOInstance<SELF>>{
	
	private final Struct<SELF> thisStruct;
	private final Object[]     virtualFields;
	
	@SuppressWarnings("unchecked")
	public IOInstance(){
		this.thisStruct=Struct.of(getClass());
		virtualFields=allocVirtual();
	}
	public IOInstance(Struct<SELF> thisStruct){
		this.thisStruct=thisStruct;
		virtualFields=allocVirtual();
	}
	
	private Object[] allocVirtual(){
		var count=(int)getThisStruct().getVirtualFields().stream().filter(c->((VirtualAccessor<SELF>)c.getAccessor()).getAccessIndex()!=-1).count();
		return count==0?null:new Object[count];
	}
	
	public Struct<SELF> getThisStruct(){
		return thisStruct;
	}
	
	public long calcSize(){
		return calcMetric(Struct::getFixedSize, f->OptionalLong.of(f.calcSize(self()))).orElseThrow();
	}
	
	private OptionalLong calcMetric(Function<Struct<SELF>, OptionalLong> fixed, Function<IOField<SELF, ?>, OptionalLong> val){
		var siz=fixed.apply(getThisStruct());
		if(siz.isPresent()) return siz;
		
		long sum=0;
		for(var f : getThisStruct().getFields()){
			var opt=val.apply(f);
			if(opt.isEmpty()) return OptionalLong.empty();
			sum+=opt.getAsLong();
		}
		return OptionalLong.of(sum);
	}
	
	//used in VirtualAccessor
	@SuppressWarnings("unused")
	private Object accessVirtual(VirtualAccessor<SELF> accessor){
		int index=accessor.getAccessIndex();
		if(index==-1) return null;
		return virtualFields[index];
	}
	@SuppressWarnings("unused")
	private void accessVirtual(VirtualAccessor<SELF> accessor, Object value){
		int index=accessor.getAccessIndex();
		if(index==-1) return;
		virtualFields[index]=value;
	}
	
	@SuppressWarnings("unchecked")
	protected final SELF self(){return (SELF)this;}
	
	@Override
	public String toString(){
		return getThisStruct().instanceToString(self(), false);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		
		if(!(o instanceof IOInstance<?> that)) return false;
		var struct=getThisStruct();
		if(that.getThisStruct()!=struct) return false;
		
		for(var field : struct.getFields()){
			if(!field.instancesEqual(self(), (SELF)that)) return false;
		}
		
		return true;
	}
	@Override
	public int hashCode(){
		int result=1;
		
		for(var field : thisStruct.getFields()){
			result=31*result+field.instanceHashCode(self());
		}
		
		return result;
	}
}
