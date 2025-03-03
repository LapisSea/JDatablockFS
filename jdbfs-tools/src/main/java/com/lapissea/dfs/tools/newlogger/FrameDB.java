package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class FrameDB{
	
	private final Cluster cluster;
	private final Lock    lock = new ReentrantLock();
	
	public FrameDB(IOInterface storage) throws IOException{
		if(storage.getIOSize() == 0){
			Cluster.init(storage);
		}
		cluster = new Cluster(storage);
		
		Thread.ofVirtual().start(() -> {
			lock.lock();
			try{
				if(cluster.roots().listAll().hasAny()){
					return;
				}
				store("\0", new IPC.FullFrame(0, new byte[0], new IPC.RangeSet(new long[0])));
				store("\0", new IPC.DiffFrame(1, 0, -1, new IPC.DiffPart[0], new IPC.RangeSet(new long[0])));
				clear("\0");
			}catch(IOException e){
				e.printStackTrace();
			}finally{
				lock.unlock();
			}
		});
	}
	
	public void store(String name, IPC.SendFrame frame) throws IOException{
		var ioFrame = switch(frame){
			case IPC.DiffFrame f -> {
				var capacity = Iters.from(f.parts()).mapToLong(p -> p.data().length).sum();
				var blob     = newSyncBlob(capacity);
				
				var parts = new ArrayList<IOFrame.Diff.Part>(f.parts().length);
				try(var io = blob.io()){
					for(var part : f.parts()){
						var offset = io.getPos();
						io.write(part.data());
						
						var start = part.offset();
						var size  = part.data().length;
						parts.add(new IOFrame.Diff.Part(offset, new IOFrame.Range(start, size)));
					}
					assert io.getPos() == capacity;
				}
				yield new IOFrame.Diff(parts, blob, f.uid(), f.prevUid(), f.newSize(), rangeSetToIO(f.writes()));
			}
			case IPC.FullFrame f -> {
				var blob = newSyncBlob(f.data().length);
				blob.set(f.data());
				yield new IOFrame.Full(f.uid(), blob, rangeSetToIO(f.writes()));
			}
		};
		store(name, ioFrame);
	}
	private Blob newSyncBlob(long capacity) throws IOException{
		Blob blob;
		lock.lock();
		try{
			blob = Blob.request(cluster, capacity);
			blob.io(io -> io.setSize(capacity));
		}finally{
			lock.unlock();
		}
		return blob;
	}
	
	private void store(String name, IOFrame ioFrame) throws IOException{
		lock.lock();
		try{
			IOMap<Long, IOFrame> frames   = getFrames(name);
			IOList<Long>         sequence = getFrameSequence(name);
			
			frames.put(ioFrame.uid(), ioFrame);
			sequence.add(ioFrame.uid());
		}finally{
			lock.unlock();
		}
	}
	private static List<IOFrame.Range> rangeSetToIO(IPC.RangeSet writes){
		var ss     = writes.startsSizes();
		var ranges = new ArrayList<IOFrame.Range>(ss.length/2);
		for(int i = 0; i<ss.length; i += 2){
			ranges.add(new IOFrame.Range(ss[i], ss[i + 1]));
		}
		return ranges;
	}
	
	public void clear(String name) throws IOException{
		lock.lock();
		try{
			cluster.roots().drop("F" + name);
			cluster.roots().drop("S" + name);
		}finally{
			lock.unlock();
		}
	}
	
	public IPC.FullFrame resolve(String name, long uid) throws IOException{
		lock.lock();
		try{
			IOMap<Long, IOFrame> frames = getFrames(name);
			
			var frame = frames.get(uid);
			if(frame == null){
				return null;
			}
			
			var data = frame.resolve(frames::get);
			
			long[] startsSizes = new long[frame.writes().size()*2];
			var    writes      = frame.writes();
			for(int i = 0; i<writes.size(); i++){
				var r = writes.get(i);
				startsSizes[i*2] = r.start;
				startsSizes[i*2 + 1] = r.size;
			}
			
			return new IPC.FullFrame(frame.uid(), data, new IPC.RangeSet(startsSizes));
		}finally{
			lock.unlock();
		}
	}
	
	private IOMap<Long, IOFrame> getFrames(String name) throws IOException{
		return cluster.roots().request("F" + name, IOMap.class, Long.class, IOFrame.class);
	}
	private IOList<Long> getFrameSequence(String name) throws IOException{
		return cluster.roots().request("S" + name, IOList.class, long.class);
	}
	
	public DBLogConnection.Session.SessionStats sequenceInfo(String name) throws IOException{
		lock.lock();
		try{
			var sequence = getFrameSequence(name);
			if(sequence == null || sequence.isEmpty()){
				return new DBLogConnection.Session.SessionStats(0, -1);
			}
			return new DBLogConnection.Session.SessionStats(sequence.size(), sequence.getLast());
		}finally{
			lock.unlock();
		}
	}
}
