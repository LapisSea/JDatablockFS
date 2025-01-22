package demo.photo;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.query.Query;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NanoTimer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class Photos{
	static{ Thread.startVirtualThread(Cluster::emptyMem); }
	
	public static void main(String[] args) throws IOException{
//		ConfigDefs.LOG_LEVEL.set(Log.LogLevel.ALL);
		PUtils.fileMemory(Photos::run);
	}
	
	private static void run(IOInterface data) throws IOException{
		LogUtil.println("Loading...");
		var guif = CompletableFuture.supplyAsync(GUI::new);
		
		Cluster database;
		try{
			database = new Cluster(data);
		}catch(Throwable e){
			if(data.getIOSize() != 0){
				e.printStackTrace();
			}
			LogUtil.println("Initializing database...");
			database = Cluster.init(data);
		}
		
		IOList<Photo> photos = database.roots().request("myPhotos", IOList.class, Photo.class);
		
		var gui = guif.join();
		gui.setTotalCount(photos.size());
		
		gui.searchChange = str -> {
			if(str.isBlank()){
				gui.updateImages(List.of());
				return;
			}
			var trim = str.trim();
			LogUtil.println(trim);
			
			NanoTimer timer = new NanoTimer.Simple();
			try{
				timer.start();
				var res = photos.where(Query.Test.field(Photo::name, n -> n.contains(trim)))
				                .limit(30)
				                .stream(s -> s.map(p -> {
					                BufferedImage img = null;
					                try{
						                img = ImageIO.read(new ByteArrayInputStream(p.data));
					                }catch(IOException e){
						                //probably a placeholder
					                }
					                if(img == null) img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
					                return new GUI.NamedImage(p.name.substring(p.name.lastIndexOf('\\')), img);
				                }).toList());
				timer.end();
				
				gui.updateImages(res);
				
			}catch(IOException e){
				e.printStackTrace();
			}
			LogUtil.println("Search done in " + timer.ms() + "ms");
		};
		
		gui.fileDrop = file -> {
			if(file.isDirectory()){
				addFolder(photos, file);
			}else if(isValidImage(file)){
				try{
					add(photos, file);
				}catch(IOException e){
					throw new RuntimeException("Failed to add image", e);
				}
			}
			gui.setTotalCount(photos.size());
		};
		
		LogUtil.println("Done!");
		
		var scanner = new Scanner(System.in);
		while(true){
			LogUtil.print("Enter command: ");
			var cmd = scanner.nextLine().split("\\s+", 2);
			
			try{
				switch(cmd[0]){
					case "exit" -> {
						LogUtil.println("Closing...");
						return;
					}
					case "add" -> add(photos, arg1F(cmd));
					case "names" -> names(photos, arg1(cmd));
					case "addFolder" -> addFolder(photos, arg1F(cmd));
					case "dummyData" -> dummyData(photos);
					case "size" -> size(data);
					case "count" -> LogUtil.println("There are " + photos.size() + " photos.");
					default -> LogUtil.println("Unknown command: " + cmd[0]);
				}
			}catch(NoArg ignore){ }
			gui.setTotalCount(photos.size());
		}
	}
	
	private static final String[] UNITS = {"B", "KB", "MB", "GB"};
	private static void size(IOInterface data) throws IOException{
		double size      = data.getIOSize();
		int    unitIndex = 0;
		
		while(size>=1024 && unitIndex<UNITS.length - 1){
			size /= 1024;
			unitIndex++;
		}
		
		System.out.printf("%.2f %s%n", size, UNITS[unitIndex]);
	}
	
	private static void add(IOList<Photo> photos, File file) throws IOException{
		photos.add(PUtils.photoFromFile(file));
	}
	
	private static void names(IOList<Photo> photos, String name) throws IOException{
		var matchPhotoNames = photos.where(Query.Test.field(Photo::name, n -> n.contains(name)))
		                            .mapF(Photo::name)
		                            .allToList();
		
		LogUtil.println(Iters.from(matchPhotoNames).enumerate((i, s) -> i + "\t" + s).joinAsStr("\n"));
	}
	
	private static void addFolder(IOList<Photo> photos, File folder){
		var lock  = new ReentrantLock();
		var count = new AtomicInteger();
		var last  = new AtomicLong();
		
		PUtils.threadedFolderScan(folder, file -> {
			if(!isValidImage(file)){
				return;
			}
//			LogUtil.println("Adding " + file.getPath());
			var photo = PUtils.photoFromFile(file);
			lock.lock();
			photos.add(photo);
			lock.unlock();
			count.incrementAndGet();
			var lastL = last.get();
			var now   = System.currentTimeMillis();
			if((now - lastL)>100){
				last.set(now);
				var old = count.getAndSet(0);
				LogUtil.println("Added", old + "files in 100ms");
			}
		});
	}
	
	private static boolean isValidImage(File file){
		return Iters.of(".jpeg", ".jpg", ".png").anyMatch(file.getPath()::endsWith);
	}
	
	private static void dummyData(IOList<Photo> photos) throws IOException{
		photos.addAll(List.of(
			new Photo("Hello world", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 111, 22, 33, 44, 55}),
			new Photo("hello Algebra", new byte[]{1, 2, 12}),
			new Photo("Coffee", new byte[]{1, 2, 3, 13}),
			new Photo("Algebra entrance", new byte[]{1, 2, 3, 4, 14}),
			new Photo("Food", new byte[]{1, 3, 34, 5, 6, 7, 15})
		));
	}
	
	
	private static final class NoArg extends Exception{ }
	
	private static String arg1(String[] cmd) throws NoArg{
		if(cmd.length<=1) throw new NoArg();
		return cmd[1];
	}
	private static File arg1F(String[] cmd) throws NoArg{
		if(cmd.length<=1) throw new NoArg();
		return new File(cmd[1]);
	}
}
