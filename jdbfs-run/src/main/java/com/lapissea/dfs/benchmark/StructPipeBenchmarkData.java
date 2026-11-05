package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

/**
 * Reproducible heterogeneous records shared by all pipe benchmark modes.
 */
public final class StructPipeBenchmarkData{
	
	private StructPipeBenchmarkData(){ }
	
	public enum Status{NEW, ACTIVE, PAUSED, RETRY, COMPLETE, FAILED, ARCHIVED}
	
	@IOValue
	@StructPipe.Special
	public static class NumericKey extends IOInstance.Managed<NumericKey>{
		@IODependency.VirtualNumSize
		private long  id;
		private int   generation;
		private short region;
	}
	
	@IOValue
	@StructPipe.Special
	public static class NumericPair extends IOInstance.Managed<NumericPair>{
		private NumericKey source;
		private NumericKey target;
		private int        weight;
		private boolean    enabled;
	}
	
	@IOValue
	@StructPipe.Special
	public static class Address extends IOInstance.Managed<Address>{
		private String city;
		private String street;
		private int    postcode;
	}
	
	@IOValue
	@StructPipe.Special
	public static class LineItem extends IOInstance.Managed<LineItem>{
		@IODependency.VirtualNumSize
		private long   sku;
		private String name;
		private int    quantity;
		private double price;
	}
	
	@IOValue
	@StructPipe.Special
	public static class DeviceSample extends IOInstance.Managed<DeviceSample>{
		@IODependency.VirtualNumSize
		private long    sequence;
		private float   temperature;
		private double  voltage;
		private boolean online;
		private boolean calibrated;
		private byte    quality;
		private short   channel;
		private int[]   counters;
		private float[] readings;
	}
	
	@IOValue
	@StructPipe.Special
	public static class Customer extends IOInstance.Managed<Customer>{
		private String            name;
		private Address           billing;
		@IONullability(NULLABLE)
		private Address           delivery;
		private List<String>      aliases;
		private Optional<Integer> loyalty;
		private boolean           verified;
	}
	
	@IOValue
	@StructPipe.Special
	public static class Order extends IOInstance.Managed<Order>{
		@IODependency.VirtualNumSize
		private long          id;
		private LineItem[]    items;
		private Integer[]     discounts;
		private List<Integer> warehouseIds;
		private Status        status;
		private String        note;
	}
	
	@IOValue
	@StructPipe.Special
	public static class Event extends IOInstance.Managed<Event>{
		private Instant            created;
		private Duration           elapsed;
		private Status[]           transitions;
		private boolean[]          flags;
		private Optional<LineItem> related;
		@IONullability(NULLABLE)
		private String             detail;
	}
	
	@IOValue
	@StructPipe.Special
	public static class Attachment extends IOInstance.Managed<Attachment>{
		private String            filename;
		private byte[]            payload;
		private List<String>      tags;
		@IONullability(NULLABLE)
		private float[]           preview;
		private Optional<Integer> revision;
		private long              checksum;
	}
	
	@IOValue
	@StructPipe.Special
	public static class TimeWindow extends IOInstance.Managed<TimeWindow>{
		private LocalDate     day;
		private LocalTime     start;
		private LocalDateTime end;
		private Duration      interval;
		private int[]         offsets;
		private boolean       recurring;
	}
	
	@IOValue
	@StructPipe.Special
	public static class LinkedDocument extends IOInstance.Managed<LinkedDocument>{
		private String   title;
		@IOValue.Reference
		private Address  author;
		@IOValue.Reference @IONullability(NULLABLE)
		private LineItem product;
		private byte[]   digest;
		private Status   status;
	}
	
	@IOValue
	@StructPipe.Special
	public static class DynamicRecord extends IOInstance.Managed<DynamicRecord>{
		private int          category;
		@IOValue.Generic @IONullability(NULLABLE)
		private Object       value;
		@IOValue.Generic @IOValue.Reference @IONullability(NULLABLE)
		private Object       linked;
		private List<String> attributes;
		private boolean      visible;
	}
	
	@IOValue
	@StructPipe.Special
	public static class Scene extends IOInstance.Managed<Scene>{
		private List<LineItem> objects;
		private List<Status>   layers;
		private String[]       labels;
		private float[]        coordinates;
		@IONullability(NULLABLE)
		private boolean[]      selection;
		private double         scale;
	}
	
	@IOValue
	@StructPipe.Special
	public static class Account extends IOInstance.Managed<Account>{
		@IODependency.VirtualNumSize
		private long             balance;
		@IONullability(NULLABLE)
		private Integer          quota;
		@IONullability(NULLABLE)
		private Long             lastTransaction;
		private Optional<String> comment;
		private Integer[]        history;
		private Address          location;
		private Status           state;
	}
	
	/**
	 * Fixed-layout candidates have bounded signed values that also fit an INT varying layout.
	 */
	public static boolean supportsFixed(IOInstance<?> value){
		return value instanceof NumericKey || value instanceof NumericPair;
	}
	
	/**
	 * Returns exactly count records cycling twelve layouts and independently varying nulls and collection lengths.
	 */
	public static List<IOInstance<?>> create(int count){
		if(count<1) throw new IllegalArgumentException("count must be positive");
		var    records = new ArrayList<IOInstance<?>>(count + 11);
		var    random  = new Random(0x51A7C0DE);
		int[]  lengths = {0, 1, 3, 9, 32, 127};
		long[] wide    = {0, -1, 127, -300, 70_000, -9_000_000_000L, Long.MAX_VALUE, Long.MIN_VALUE};
		for(int i = 0; records.size()<count; i++){
			int  n        = lengths[i%lengths.length];
			long number   = wide[i%wide.length];
			var  integers = new int[n];
			var  boxed    = new Integer[n];
			var  floats   = new float[n];
			var  flags    = new boolean[n];
			var  bytes    = new byte[n*3];
			var  choices  = new Status[n];
			var  items    = new LineItem[n];
			var  labels   = new String[n];
			for(int j = 0; j<n; j++){
				integers[j] = j%3 == 0? random.nextInt() : j%2 == 0? -300 - j : j;
				boxed[j] = integers[j];
				floats[j] = (random.nextFloat() - 0.5F)*1000;
				flags[j] = random.nextBoolean();
				choices[j] = Status.values()[(i + j)%Status.values().length];
				items[j] = item(i + j, wide[(i + j)%wide.length]);
				labels[j] = text(i + j);
			}
			random.nextBytes(bytes);
			
			var device = new DeviceSample();
			device.sequence = number;
			device.temperature = i*0.25F - 20;
			device.voltage = 3.3 + i*0.01;
			device.online = i%2 == 0;
			device.calibrated = i%3 == 0;
			device.quality = (byte)i;
			device.channel = (short)(i - 300);
			device.counters = integers;
			device.readings = floats;
			records.add(device);
			
			var customer = new Customer();
			customer.name = text(i);
			customer.billing = address(i);
			customer.delivery = i%3 == 0? null : address(i + 1);
			customer.aliases = List.of(labels);
			customer.loyalty = i%2 == 0? Optional.empty() : Optional.of(integers.length + i);
			customer.verified = i%2 == 0;
			records.add(customer);
			
			var order = new Order();
			order.id = number;
			order.items = items;
			order.discounts = boxed;
			order.warehouseIds = new ArrayList<>(List.of(boxed));
			order.status = Status.values()[i%Status.values().length];
			order.note = text(i + 2);
			records.add(order);
			
			var event = new Event();
			event.created = Instant.ofEpochSecond(1_700_000_000L + i*719L, i*123456L);
			event.elapsed = Duration.ofSeconds(i%2 == 0? -i : i, 999_999_999);
			event.transitions = choices;
			event.flags = flags;
			event.related = i%3 == 0? Optional.empty() : Optional.of(item(i, number));
			event.detail = i%4 == 0? null : text(i + 4);
			records.add(event);
			
			var attachment = new Attachment();
			attachment.filename = "sample-" + i + ".bin";
			attachment.payload = bytes;
			attachment.tags = new ArrayList<>(List.of(labels));
			attachment.preview = i%3 == 0? null : floats;
			attachment.revision = i%2 == 0? Optional.of(i) : Optional.empty();
			attachment.checksum = random.nextLong();
			records.add(attachment);
			
			var window = new TimeWindow();
			window.day = LocalDate.of(2020, 1, 1).plusDays(i);
			window.start = LocalTime.ofNanoOfDay((i*1_234_567_890L)%86_400_000_000_000L);
			window.end = LocalDateTime.of(window.day.plusDays(1), window.start);
			window.interval = Duration.ofNanos(1_000_000L + i*271L);
			window.offsets = integers;
			window.recurring = i%2 == 0;
			records.add(window);
			
			var document = new LinkedDocument();
			document.title = text(i + 5);
			document.author = address(i + 2);
			document.product = i%3 == 0? null : item(i, number);
			document.digest = bytes;
			document.status = order.status;
			records.add(document);
			
			var dynamic = new DynamicRecord();
			dynamic.category = i%4;
			dynamic.value = switch(i%4){ case 0 -> null; case 1 -> text(i); case 2 -> integers.length; default -> address(i); };
			dynamic.linked = switch(i%4){ case 0 -> item(i, number); case 1 -> null; case 2 -> text(i); default -> -70_000 - i; };
			dynamic.attributes = List.of(labels);
			dynamic.visible = i%2 == 0;
			records.add(dynamic);
			
			var scene = new Scene();
			scene.objects = new ArrayList<>(List.of(items));
			scene.layers = List.of(choices);
			scene.labels = labels;
			scene.coordinates = floats;
			scene.selection = i%3 == 0? null : flags;
			scene.scale = 1.0/(i + 1);
			records.add(scene);
			
			var account = new Account();
			account.balance = number;
			account.quota = i%3 == 0? null : -70_000 - i;
			account.lastTransaction = i%2 == 0? number : null;
			account.comment = i%3 == 0? Optional.empty() : Optional.of(text(i));
			account.history = boxed;
			account.location = address(i + 3);
			account.state = order.status;
			records.add(account);
			
			records.add(key(i));
			var pair = new NumericPair();
			pair.source = key(i + 1);
			pair.target = key(i + 2);
			pair.weight = i*125;
			pair.enabled = i%2 == 0;
			records.add(pair);
		}
		return List.copyOf(records.subList(0, count));
	}
	
	private static String text(int i){
		return switch(i%4){
			case 0 -> "";
			case 1 -> "record-" + i;
			case 2 -> "Zagreb / München / 東京 " + i;
			default -> "A longer description of a stored record with multiple attributes " + i;
		};
	}
	
	private static Address address(int i){
		var value = new Address();
		value.city = text(i);
		value.street = "Street " + i;
		value.postcode = 10_000 + i;
		return value;
	}
	
	private static LineItem item(int i, long sku){
		var value = new LineItem();
		value.sku = sku;
		value.name = text(i);
		value.quantity = i%19;
		value.price = (i + 1)*2.75;
		return value;
	}
	
	private static NumericKey key(int i){
		var value = new NumericKey();
		value.id = switch(i%4){ case 0 -> 0; case 1 -> -300 - i; case 2 -> 70_000 + i; default -> Integer.MIN_VALUE + i; };
		value.generation = i;
		value.region = (short)(i - 300);
		return value;
	}
}
