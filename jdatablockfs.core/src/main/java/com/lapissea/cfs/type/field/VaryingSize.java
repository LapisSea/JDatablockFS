package com.lapissea.cfs.type.field;

import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Stringify;
import com.lapissea.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
		
		final class Repeater implements Provider{
			private final List<NumberSize> data;
			private       int              counter;
			
			public Repeater(List<NumberSize> data){
				this.data=List.copyOf(data);
			}
			
			@Override
			public VaryingSize provide(NumberSize max){
				var id    =counter;
				var actual=data.get(id);
				counter++;
				return new VaryingSize(actual, id);
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
		static Provider repeat(List<NumberSize> data){
			return new Repeater(data);
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
	
	public static final class TooSmall extends RuntimeException{
		public final Map<VaryingSize, NumberSize> tooSmallIdMap;
		
		public TooSmall(Map<VaryingSize, NumberSize> tooSmallIdMap){
			this.tooSmallIdMap=Map.copyOf(tooSmallIdMap);
		}
		public TooSmall(VaryingSize size, NumberSize neededSize){
			this(Map.of(size, neededSize));
		}
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName()+": "+TextUtil.toString(tooSmallIdMap);
		}
	}
	
	public final  NumberSize size;
	private final int        id;
	
	public VaryingSize(NumberSize size, int id){
		if(id<0&&id!=-1) throw new IllegalArgumentException();
		this.size=Objects.requireNonNull(size);
		this.id=id;
	}
	
	public NumberSize safeNumber(long neededNum){
		return safeSize(NumberSize.bySize(neededNum));
	}
	public NumberSize safeSize(NumberSize neededSize){
		if(neededSize.greaterThan(size)){
			if(id==-1) throw new UnsupportedOperationException();
			throw new TooSmall(this, neededSize);
		}
		return size;
	}
	
	public int getId(){
		return id;
	}
	
	@Override
	public String toString(){
		return "VaryingSize"+toShortString();
	}
	@Override
	public String toShortString(){
		return "{"+size+(id==-1?"":" @"+id)+'}';
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||o instanceof VaryingSize that&&id==that.id&&size==that.size;
		
	}
	@Override
	public int hashCode(){
		return 31*size.hashCode()+id;
	}
}
