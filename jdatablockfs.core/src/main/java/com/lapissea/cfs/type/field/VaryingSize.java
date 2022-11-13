package com.lapissea.cfs.type.field;

import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Stringify;
import com.lapissea.util.TextUtil;

import java.util.*;
import java.util.function.BiConsumer;

public class VaryingSize implements Stringify{
	
	public static final VaryingSize MAX=new VaryingSize(NumberSize.LARGEST, -1);
	
	public interface Provider{
		
		final class Recorder implements Provider{
			
			public interface Mapper{
				NumberSize map(NumberSize max, boolean ptr, int id);
			}
			
			private final List<NumberSize> data=new ArrayList<>();
			private final Mapper           mapper;
			
			private final Map<Integer, Integer> marks=new HashMap<>();
			private       int                   markIdCount;
			
			public Recorder(Mapper mapper){
				this.mapper=mapper;
			}
			
			@Override
			public VaryingSize provide(NumberSize max, boolean ptr){
				var id    =data.size();
				var actual=mapper.map(max, ptr, id);
				data.add(actual);
				return new VaryingSize(actual, id);
			}
			
			@Override
			public int mark(){
				int id=markIdCount++;
				marks.put(id, data.size());
				return id;
			}
			@Override
			public void reset(int id){
				var size=marks.get(id);
				while(data.size()>size){
					data.remove(data.size()-1);
				}
				marks.entrySet().removeIf(e->e.getKey()>=id);
			}
			
			public List<NumberSize> export(){
				return List.copyOf(data);
			}
		}
		
		final class Repeater implements Provider{
			private final List<NumberSize> data;
			private       int              counter;
			
			private final Map<Integer, Integer> marks=new HashMap<>();
			private       int                   markIdCount;
			
			public Repeater(List<NumberSize> data){
				this.data=List.copyOf(data);
			}
			
			@Override
			public VaryingSize provide(NumberSize max, boolean ptr){
				var id    =counter;
				var actual=data.get(id);
				counter++;
				return new VaryingSize(actual, id);
			}
			@Override
			public int mark(){
				int id=markIdCount++;
				marks.put(id, counter);
				return id;
			}
			@Override
			public void reset(int id){
				counter=marks.get(id);
				marks.entrySet().removeIf(e->e.getKey()>=id);
			}
		}
		
		Provider ALL_MAX=new NoMark(){
			@Override
			public VaryingSize provide(NumberSize max, boolean ptr){
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
			return new NoMark(){
				@Override
				public VaryingSize provide(NumberSize max, boolean ptr){
					return new VaryingSize(max.min(size), id);
				}
				@Override
				public String toString(){
					return "ConstLimit("+size+")";
				}
			};
		}
		
		static Provider intercept(Provider src, BiConsumer<NumberSize, VaryingSize> intercept){
			return new Provider(){
				@Override
				public VaryingSize provide(NumberSize max, boolean ptr){
					var actual=src.provide(max, ptr);
					intercept.accept(max, actual);
					return actual;
				}
				@Override
				public int mark(){
					return src.mark();
				}
				@Override
				public void reset(int id){
					src.reset(id);
				}
			};
		}
		VaryingSize provide(NumberSize max, boolean ptr);
		
		interface NoMark extends Provider{
			@Override
			default int mark(){return 0;}
			@Override
			default void reset(int id){}
		}
		
		/**
		 * Notates the start of an object.
		 *
		 * @return a unique ID
		 */
		int mark();
		/**
		 * Ignores all sizes since the start of the mark. Use when creating an object fails.
		 */
		void reset(int id);
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
