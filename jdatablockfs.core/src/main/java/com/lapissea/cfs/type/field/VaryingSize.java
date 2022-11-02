package com.lapissea.cfs.type.field;

import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Stringify;

import java.util.ArrayList;
import java.util.List;

public class VaryingSize implements Stringify{
	
	public static final VaryingSize MAX=new VaryingSize(NumberSize.LARGEST, -1);
	
	public interface Provider{
		
		final class Recorder implements Provider{
			
			public interface Mapper{
				NumberSize map(NumberSize max, int id);
			}
			
			private final List<NumberSize> data=new ArrayList<>();
			private final Mapper           mapper;
			
			public Recorder(Mapper mapper){
				this.mapper=mapper;
			}
			
			@Override
			public VaryingSize provide(NumberSize max){
				var id    =data.size();
				var actual=mapper.map(max, id);
				data.add(actual);
				return new VaryingSize(actual, id);
			}
			
			public List<NumberSize> export(){
				return List.copyOf(data);
			}
		}
		
		Provider ALL_MAX=new Provider(){
			@Override
			public VaryingSize provide(NumberSize max){
				return new VaryingSize(max, -1);
			}
			@Override
			public String toString(){
				return "ALL_MAX";
			}
		};
		
		static Recorder record(Recorder.Mapper mapper){
			return new Recorder(mapper);
		}
		
		static Provider constLimit(NumberSize size, int id){
			if(size==NumberSize.LARGEST) return ALL_MAX;
			return new Provider(){
				@Override
				public VaryingSize provide(NumberSize max){
					return new VaryingSize(max.min(size), id);
				}
				@Override
				public String toString(){
					return "ConstLimit("+size+")";
				}
			};
		}
		VaryingSize provide(NumberSize max);
	}
	
	public static final class TooSmallVarying extends RuntimeException{
		public final VaryingSize size;
		
		public TooSmallVarying(VaryingSize size){
			this.size=size;
		}
		@Override
		public String toString(){
			return this.getClass().getSimpleName()+": "+size;
		}
	}
	
	public final  NumberSize size;
	private final int        id;
	
	public VaryingSize(NumberSize size, int id){
		this.size=size;
		this.id=id;
	}
	
	public NumberSize safeNumber(long neededNum){
		return safeSize(NumberSize.bySize(neededNum));
	}
	public NumberSize safeSize(NumberSize neededSize){
		if(neededSize.greaterThan(size)){
			if(id==-1) throw new UnsupportedOperationException();
			throw new TooSmallVarying(this);
		}
		return size;
	}
	
	public int getId(){
		return id;
	}
	@Override
	public String toString(){
		return "VaryingSize{"+size+(id==-1?"":" @"+id)+'}';
	}
	@Override
	public String toShortString(){
		return "{"+size+(id==-1?"":" @"+id)+'}';
	}
}
