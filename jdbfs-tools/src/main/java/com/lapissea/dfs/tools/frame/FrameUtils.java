package com.lapissea.dfs.tools.frame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.OptionalInt;

public final class FrameUtils{
	
	public record DiffBlock(int offset, byte[] data){
		@Override
		public String toString(){
			return "Block{" +
			       "offset=" + offset +
			       ", data=" + Arrays.toString(data) +
			       '}';
		}
	}
	
	public record FrameDiffResult(DiffBlock[] blocks, OptionalInt newSize){ }
	
	public static FrameDiffResult computeDiff(byte[] last, byte[] current){
		
		final class Range{
			private int from, to;
			Range(int from, int to){
				this.from = from;
				this.to = to;
			}
		}
		var   ranges    = new ArrayList<Range>();
		Range lastRange = null;
		for(int i = 0, len = Math.min(last.length, current.length); i<len; i++){
			if(last[i] == current[i]) continue;
			if(lastRange == null){
				lastRange = new Range(i, i + 1);
				ranges.add(lastRange);
			}else if(lastRange.to == i){
				lastRange.to++;
			}else{
				lastRange = new Range(i, i + 1);
				ranges.add(lastRange);
			}
		}
		var blocks = new DiffBlock[ranges.size() + (current.length>last.length? 1 : 0)];
		for(int i = 0; i<ranges.size(); i++){
			var range = ranges.get(i);
			blocks[i] = new DiffBlock(range.from, Arrays.copyOfRange(current, range.from, range.to));
		}
		if(current.length>last.length){
			blocks[ranges.size()] = new DiffBlock(last.length, Arrays.copyOfRange(current, last.length, current.length));
		}
		
		
		return new FrameDiffResult(blocks, current.length == last.length? OptionalInt.empty() : OptionalInt.of(current.length));
	}
	
}
