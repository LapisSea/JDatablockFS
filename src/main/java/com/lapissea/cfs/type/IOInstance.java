package com.lapissea.cfs.type;

import java.util.OptionalLong;
import java.util.function.Function;

public class IOInstance<SELF extends IOInstance<SELF>>{
	private final Struct<SELF> thisStruct;
	
	@SuppressWarnings("unchecked")
	public IOInstance(){
		this.thisStruct=Struct.of(getClass());
	}
	public IOInstance(Struct<SELF> thisStruct){
		this.thisStruct=thisStruct;
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
	
	@SuppressWarnings("unchecked")
	protected SELF self(){return (SELF)this;}
	
	@Override
	public String toString(){
		return getThisStruct().instanceToString(self(), false);
	}
}
