package demo.photo.db;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.util.LogUtil;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ThumbnailDB{
	
	private final IOMap<String, IconData> icons;
	private final ReadWriteLock           iconsLock = new ReentrantReadWriteLock();
	
	public ThumbnailDB(){
		try{
			LogUtil.println("Icon database: ", new File("./icons.dfs").getAbsoluteFile().toPath().normalize());
			
			var db = Cluster.initOrOpen(new FileMemoryMappedData("./icons.dfs"));
			icons = db.roots().request("icons", IOMap.class, String.class, IconData.class);
		}catch(IOException e){
			throw new RuntimeException("Failed to initialize database", e);
		}
	}
	
	public void putThumbnail(File file, BufferedImage image) throws IOException{
		var icon = IconData.from(image);
		setIcon(file.getPath(), icon);
	}
	public BufferedImage readThumbnail(File file) throws IOException{
		IconData icon = getIcon(file.getPath());
		if(icon == null){
			return null;
		}
		return icon.parse();
	}
	
	private IconData getIcon(String path) throws IOException{
		IconData icon;
		
		var lock = iconsLock.readLock();
		lock.lock();
		try{
			icon = icons.get(path);
		}finally{
			lock.unlock();
		}
		return icon;
	}
	
	private void setIcon(String path, IconData data) throws IOException{
		var lock = iconsLock.writeLock();
		lock.lock();
		try{
			icons.put(path, data);
		}catch(Throwable e){
			e.printStackTrace();
			throw e;
		}finally{
			lock.unlock();
		}
	}
	private void removeIcon(String path) throws IOException{
		var lock = iconsLock.writeLock();
		lock.lock();
		try{
			icons.remove(path);
		}catch(Throwable e){
			e.printStackTrace();
			throw e;
		}finally{
			lock.unlock();
		}
	}
	
	public boolean iconExists(File thumbnailFile){
		var lock = iconsLock.readLock();
		lock.lock();
		try{
			return icons.containsKey(thumbnailFile.getPath());
		}catch(IOException e){
			throw new RuntimeException("Failed to check if icon exists", e);
		}finally{
			lock.unlock();
		}
	}
}
