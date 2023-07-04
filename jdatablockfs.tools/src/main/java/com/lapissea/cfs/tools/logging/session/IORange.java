package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

@IOInstance.Def.Order({"from", "to"})
@IOInstance.Def.ToString.Format("{@from - @to}")
public interface IORange extends IOInstance.Def<IORange>{
	
	static LongStream idx(List<IORange> idx){
		return idx.stream().flatMapToLong(IORange::idx);
	}
	
	static List<IORange> fromIdx(LongStream idx){
		class Builder{
			long from, to;
			public Builder(long idx){
				from = idx;
				to = idx + 1;
			}
		}
		var build = new ArrayList<Builder>();
		idx.forEach(l -> {
			if(build.isEmpty()){
				build.add(new Builder(l));
				return;
			}
			
			var last = build.get(build.size() - 1);
			if(last.to == l){
				last.to++;
				return;
			}
			
			for(var b : build){
				if(b.to == l){
					b.to++;
					return;
				}
				if(b.from - 1 == l){
					b.from--;
					return;
				}
			}
			
			build.add(new Builder(l));
		});
		
		return build.stream().map(r -> of(r.from, r.to)).toList();
	}
	
	/**
	 * inclusive
	 */
	@IOValue.Unsigned
	@IODependency.VirtualNumSize
	long from();
	/**
	 * exclusive
	 */
	@IODependency.VirtualNumSize
	@IOValue.Unsigned
	long to();
	
	static IORange of(long from, long to){
		class Ctor{
			private static final MethodHandle VAL = Def.dataConstructor(IORange.class);
		}
		try{
			return (IORange)Ctor.VAL.invoke(from, to);
		}catch(Throwable e){
			throw new RuntimeException(e);
		}
	}
	
	default LongStream idx(){
		return LongStream.range(this.from(), to());
	}
}
