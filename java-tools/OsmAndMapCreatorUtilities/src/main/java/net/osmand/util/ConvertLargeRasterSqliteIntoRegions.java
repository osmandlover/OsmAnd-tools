package net.osmand.util;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferShort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.imageio.ImageIO;

import org.apache.commons.logging.Log;
import org.xmlpull.v1.XmlPullParserException;

import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapIndexReader.MapIndex;
import net.osmand.data.LatLon;
import net.osmand.data.Multipolygon;
import net.osmand.data.MultipolygonBuilder;
import net.osmand.data.QuadRect;
import net.osmand.data.Ring;
import net.osmand.map.OsmandRegions;
import net.osmand.obf.preparation.DBDialect;
import net.osmand.osm.edit.Node;
import net.osmand.osm.edit.Way;

public class ConvertLargeRasterSqliteIntoRegions {
	private static final Log LOG = PlatformUtil.getLog(ConvertLargeRasterSqliteIntoRegions.class);
	private static int MIN_ZOOM = 1;
	private static int MAX_ZOOM = 11;
	private static boolean SKIP_EXISTING = true;
	private static String EXTENSION = ".sqlitedb";
	private static String MERGE_TILE_FORMAT = ""; // tif
	private static final int BATCH_SIZE = 100;
	
	public static void main(String[] args) throws IOException {
		File sqliteFile = new File(args[0]);
		File directoryWithTargetFiles = new File(args[1]);
		boolean dryRun = false;
		String filter = null; // mauritius
		String prefix = "Hillshade_";
		String regionAttribute = null;
		
		for (int i = 2; i < args.length; i++) {
			if ("--dry-run".equals(args[i])) {
				dryRun = true;
			} else if (args[i].startsWith("--prefix=")) {
				prefix = args[i].substring("--prefix=".length());
			} else if (args[i].startsWith("--skip-existing=")) {
				SKIP_EXISTING = Boolean.parseBoolean(args[i].substring("--skip-existing=".length()));
			} else if (args[i].startsWith("--extension=")) {
				EXTENSION = args[i].substring("--extension=".length());
			} else if (args[i].startsWith("--merge-tile-format=")) {
				MERGE_TILE_FORMAT = args[i].substring("--merge-tile-format=".length());
			} else if (args[i].startsWith("--maxzoom=")) {
				MAX_ZOOM = Integer.parseInt(args[i].substring("--maxzoom=".length()));
			} else if (args[i].startsWith("--minzoom=")) {
				MIN_ZOOM = Integer.parseInt(args[i].substring("--minzoom=".length()));
			} else if (args[i].startsWith("--region-ocbf-attr=")) {
				// "region_hillshade"
				regionAttribute = args[i].substring("--region-ocbf-attr=".length());
				
			} else if (args[i].startsWith("--filter=")) {
				filter = args[i].substring("--filter=".length());
				if (filter.length() == 0) {
					filter = null;
				}
			}
		}
		OsmandRegions or = new OsmandRegions();
		BinaryMapIndexReader fl = or.prepareFile();
		Map<String, LinkedList<BinaryMapDataObject>> allCountries = or.cacheAllCountries();
		MapIndex mapIndex = fl.getMapIndexes().get(0);
		int regAttr = Algorithms.isEmpty(regionAttribute) ? -1 : mapIndex.getRule(regionAttribute, "yes");
		int downloadName = mapIndex.getRule("download_name", null);
		int boundary = mapIndex.getRule("osmand_region", "boundary");
		int cnt = 1;
		Set<String> failedCountries = new HashSet<String>();
		for (String fullName : allCountries.keySet()) {
			LinkedList<BinaryMapDataObject> lst = allCountries.get(fullName);
			if (fullName == null || (filter != null && !fullName.contains(filter))) {
				continue;
			}
			BinaryMapDataObject rc = null;
			for (BinaryMapDataObject r : lst) {
				if (!r.containsType(boundary)) {
					rc = r;
					break;
				}
			}
			if (rc != null && (regAttr == -1 || rc.containsAdditionalType(regAttr))) {
				String dw = rc.getNameByType(downloadName);
				System.out.println("Region " + fullName + " " + cnt++ + " out of " + allCountries.size());
				try {
					process(rc, lst, dw, sqliteFile, directoryWithTargetFiles, prefix, dryRun);
				} catch (Exception e) {
					failedCountries.add(fullName);
					e.printStackTrace();
				}
			} else {
				System.out.println(String.format("Region %s skipped as it doesn't attribute %s = yes. ", fullName, regionAttribute));
			}
		}
		if(!failedCountries.isEmpty()) {
			throw new IllegalStateException("Failed countries " + failedCountries);
		}
	}

	private static void process(BinaryMapDataObject country, List<BinaryMapDataObject> boundaries,
			String downloadName, File sqliteFile, File directoryWithTargetFiles, String prefix, boolean dryRun) throws IOException, SQLException, InterruptedException, XmlPullParserException {
		String name = country.getName();
		String dwName = prefix + Algorithms.capitalizeFirstLetterAndLowercase(downloadName) + EXTENSION;
		Set<Long> allTileNames = new TreeSet<>();
		Map<String, Set<Long>> tileNamesByFile = new TreeMap<>();
		final File targetFile = new File(directoryWithTargetFiles, dwName);
		if (!SKIP_EXISTING) {
			targetFile.delete();
		}
		if (targetFile.exists()) {
			System.out.println("Already processed "+ name);
			return;
		}

		QuadRect qr = new QuadRect(180, -90, -180, 90);
		MultipolygonBuilder bld = new MultipolygonBuilder();
		if(boundaries != null) {
			for (BinaryMapDataObject o : boundaries) {
				bld.addOuterWay(convertToWay(o));
				updateBbox(o, qr);
			}
		} else {
			bld.addOuterWay(convertToWay(country));
			updateBbox(country, qr);			
		}
		Multipolygon polygon  = bld.build();
		int rightLon = (int) Math.floor(MapUtils.getTileNumberX(MAX_ZOOM, qr.right));
		int leftLon = (int) Math.floor(MapUtils.getTileNumberX(MAX_ZOOM, qr.left));
		int bottomLat = (int) Math.floor(MapUtils.getTileNumberY(MAX_ZOOM, qr.bottom));
		int topLat = (int) Math.floor(MapUtils.getTileNumberY(MAX_ZOOM, qr.top));
		boolean onetile = leftLon == rightLon && bottomLat == topLat;
		for (int tileX = leftLon; tileX <= rightLon; tileX++) {
			for (int tileY = topLat; tileY <= bottomLat; tileY++) {
				double llon = MapUtils.getLongitudeFromTile(MAX_ZOOM, tileX);
				double rlon = MapUtils.getLongitudeFromTile(MAX_ZOOM, tileX + 1);
				double tlat = MapUtils.getLatitudeFromTile(MAX_ZOOM, tileY);
				double blat = MapUtils.getLatitudeFromTile(MAX_ZOOM, tileY + 1);
				boolean isOut = !polygon.containsPoint(tlat / 2 + blat / 2, llon / 2 + rlon / 2) && !onetile;
				if (isOut) {
					LatLon bl = new LatLon(blat, llon);
					LatLon br = new LatLon(blat, rlon);
					LatLon tr = new LatLon(tlat, rlon);
					LatLon tl = new LatLon(tlat, llon);
					for (Ring r : polygon.getOuterRings()) {
						List<Node> border = r.getBorder();
						Node prev = border.get(border.size() - 1);
						for (int i = 0; i < border.size() && isOut; i++) {
							Node n = border.get(i);
							if (MapAlgorithms.linesIntersect(prev.getLatLon(), n.getLatLon(), tr, tl)) {
								isOut = false;
							} else if (MapAlgorithms.linesIntersect(prev.getLatLon(), n.getLatLon(), tr, br)) {
								isOut = false;
							} else if (MapAlgorithms.linesIntersect(prev.getLatLon(), n.getLatLon(), bl, tl)) {
								isOut = false;
							} else if (MapAlgorithms.linesIntersect(prev.getLatLon(), n.getLatLon(), br, bl)) {
								isOut = false;
							}
							prev = n;
						}
						if (!isOut) {
							break;
						}
					}
				}
				if (!isOut) {
					int x = tileX;
					int y = tileY;
					for (int z = MAX_ZOOM; z >= MIN_ZOOM; z--) {
						// we will need to merge tiles later
						allTileNames.add(pack(x, y, z));
						String nm = getTileName((int) (tlat / 2 + blat / 2), (int) (llon / 2 + rlon / 2));
						if (!tileNamesByFile.containsKey(nm)) {
							tileNamesByFile.put(nm, new TreeSet<>());
						}
						tileNamesByFile.get(nm).add(pack(x, y, z));
						x = x >> 1;
						y = y >> 1;
					}
				}
			}
		}
		System.out.println();
		System.out.println("-----------------------------");
		System.out.println("PROCESSING " + name + " lon [" + leftLon + " - " + rightLon + "] lat [" + bottomLat + " - "
				+ topLat + "] TOTAL " + allTileNames.size() + " tiles ");
		if (dryRun) {
			return;
		}
		Set<Long> addedTileNames = new TreeSet<>();
		if (sqliteFile.isDirectory()) {
			// process by tile
			boolean first = true;
			for (Entry<String, Set<Long>> entry : tileNamesByFile.entrySet()) {
				File sqliteInFile = new File(sqliteFile, entry.getKey() + EXTENSION);
				if (!sqliteInFile.exists()) {
					System.out.println("Input tile doesn't exist " + sqliteInFile.getName());
				} else {
					procFile(sqliteInFile, targetFile, entry.getValue(), first, addedTileNames);
					first = false;
				}
			}
		} else {
			procFile(sqliteFile, targetFile, allTileNames, true, addedTileNames);
		}
	}

	private static String getTileName(int lt, int ln) {
		String id = "";
		if(lt >= 0) {
			id += "N";
		} else {
			id += "S";
		}
		lt = Math.abs(lt);
		if(lt < 10) {
			id += "0";
		}
		id += lt;
		
		if(ln >= 0) {
			id += "E";
		} else {
			id += "W";
		}
		ln = Math.abs(ln);
		if(ln < 10) {
			id += "0";
		}
		if(ln < 100) {
			id += "0";
		}
		id += ln;
		return id;
	}
	

	private static void procFile(File sqliteFile, final File targetFile, Set<Long> tileNames, boolean initDb, 
			Set<Long> addedBeforeTileNames)
			throws IOException, SQLException {
		File procFile = new File(targetFile.getParentFile(), targetFile.getName() + ".proc");
		boolean locked = !procFile.createNewFile();
		if (locked) {
			System.out.println("\n\n!!!!!!!! WARNING FILE IS BEING PROCESSED !!!!!!!!!\n\n");
			return;
		}
		int batch = 0;
		try (Connection sqliteConn = DBDialect.SQLITE.getDatabaseConnection(sqliteFile.getAbsolutePath(), LOG);
				Connection newFile = DBDialect.SQLITE.getDatabaseConnection(targetFile.getAbsolutePath(), LOG)) {
			if (initDb) {
				prepareNewHillshadeFile(newFile, false, MIN_ZOOM, MAX_ZOOM);
			}
			PreparedStatement ps = sqliteConn
					.prepareStatement("SELECT image FROM tiles WHERE x = ? AND y = ? AND z = ?");
			PreparedStatement psnew = newFile
					.prepareStatement("SELECT image FROM tiles WHERE x = ? AND y = ? AND z = ?");
			PreparedStatement psdel = newFile
					.prepareStatement("DELETE FROM tiles WHERE x = ? AND y = ? AND z = ?");
			PreparedStatement is = newFile
					.prepareStatement("INSERT INTO tiles(x, y, z, s, image) VALUES(?, ?, ?, 0, ?)");
			for (long s : tileNames) {
				boolean added = addedBeforeTileNames.add(s);
				int x = unpack1(s);
				int y = unpack2(s);
				int z = unpack3(s);
				ps.setInt(1, x);
				ps.setInt(2, y);
				ps.setInt(3, z);
				// int yt = (1 << z) - y - 1;
				ResultSet rs = ps.executeQuery();
				if (rs.next()) {
					byte[] image = rs.getBytes(1);
					if (!added) {
						// here we merge 2 images
						if (!Algorithms.isEmpty(MERGE_TILE_FORMAT)) {
							psnew.setInt(1, x);
							psnew.setInt(2, y);
							psnew.setInt(3, z);
							ResultSet rsnew = ps.executeQuery();
							if (!rsnew.next()) {
								throw new IllegalStateException();
							}
							image = mergeImages(image, rsnew.getBytes(1));
							rsnew.close();
							if (image != null) {
								psdel.setInt(1, x);
								psdel.setInt(2, y);
								psdel.setInt(3, z);
								psdel.execute();
							}
						} else {
							image = null;
						}
					}
					if (image != null) {
						is.setInt(1, x);
						is.setInt(2, y);
						is.setInt(3, z);
						is.setBytes(4, image);
						is.addBatch();

						if (batch++ >= BATCH_SIZE) {
							batch = 0;
							is.executeBatch();
						}
					}
				}
				rs.close();
			}
			is.executeBatch();
			is.close();
			ps.close();
		}
		;
		procFile.delete();
	}
	
	

	

	private static void prepareNewHillshadeFile(Connection newFile, boolean bigPlanet, int minZoom, int maxZoom) throws SQLException {
		Statement statement = newFile.createStatement();
		statement.execute("CREATE TABLE tiles (x int, y int, z int, s int, image blob, time long, PRIMARY KEY (x,y,z,s))");
		statement.execute("CREATE INDEX IND on tiles (x,y,z,s)");
		statement.execute("CREATE TABLE info(tilenumbering,minzoom,maxzoom,timecolumn,url,rule,referer)");
		statement.close();


		PreparedStatement pStatement = newFile.prepareStatement("INSERT INTO INFO VALUES(?,?,?,?,?,?,?)");
		String tileNumbering = bigPlanet ? "BigPlanet" : "simple";
		pStatement.setString(1, tileNumbering);
		int minNormalZoom = bigPlanet ? 17 - maxZoom : minZoom;
		int maxNormalZoom = bigPlanet ? 17 - minZoom : maxZoom;
		pStatement.setInt(2, minNormalZoom);
		pStatement.setInt(3, maxNormalZoom);
		pStatement.setString(4, "yes");
		pStatement.setString(5, "");
		pStatement.setString(6, "");
		pStatement.setString(7, "");
		pStatement.execute();
	}
	
	// @Native public static final long MAX_VALUE = 0x7fffffffffffffffL;
	// @Native public static final int  MAX_VALUE = 0x7fffffff         ;
	private static final long MASK1 = 0x00000000ffffff00L;
	private static final long SHIFT_1 = 8;
	private static final long MASK2 = 0x00ffffff00000000L;
	private static final long SHIFT_2 = 32;
	private static final long MASK3 = 0x00000000000000ffl;
	private static final long SHIFT_3 = 0;
	private static long pack(int r1, int r2, int r3) {
		long l = 0;
		l |= ((long)r1 << SHIFT_1) & MASK1;
		l |= ((long)r2 << SHIFT_2) & MASK2;
		l |= ((long)r3 << SHIFT_3) & MASK3;
		if(unpack3(l) != r3  || unpack2(l) != r2  || unpack1(l) != r1 ) {
			throw new IllegalStateException();
		}
		return l;
	}
	
	private static int unpack3(long l) {
		return (int) ((l & MASK3) >> SHIFT_3);
	}
	
	private static int unpack2(long l) {
		return (int) ((l & MASK2) >> SHIFT_2);
	}
	
	private static int unpack1(long l) {
		return (int) ((l & MASK1) >> SHIFT_1);
	}

	private static Way convertToWay(BinaryMapDataObject o) {
		Way w = new Way(-1);
		for(int i = 0; i < o.getPointsLength(); i++) {
			double lat = MapUtils.get31LatitudeY(o.getPoint31YTile(i));
			double lon = MapUtils.get31LongitudeX(o.getPoint31XTile(i));
			w.addNode(new Node(lat, lon, -1));
		}
		return w;
	}



	private static void updateBbox(BinaryMapDataObject country, QuadRect qr) {
		for(int i = 0; i < country.getPointsLength(); i++) {
			double lat = MapUtils.get31LatitudeY(country.getPoint31YTile(i));
			double lon = MapUtils.get31LongitudeX(country.getPoint31XTile(i));
			qr.left = Math.min(lon, qr.left);
			qr.right = Math.max(lon, qr.right);
			qr.top = Math.max(lat, qr.top);
			qr.bottom = Math.min(lat, qr.bottom);
		}
	}

	
	private static byte[] mergeImages(byte[] image, byte[] bsimage) throws IOException {
		File f1 = new File("_img." + EXTENSION);
		File f2 = new File("_overlay." + EXTENSION);
		File fOut = new File("_res." + EXTENSION);
		BufferedImage b1 = ImageIO.read(f1);
		BufferedImage b2 = ImageIO.read(f2);
		DataBufferShort data1 = (DataBufferShort) b1.getRaster().getDataBuffer();
		DataBufferShort data2 = (DataBufferShort) b2.getRaster().getDataBuffer();
		for(int i = 0; i < data1.getSize() && i < data2.getSize(); i++) {
			data1.setElem(i, Math.max(data1.getElem(i), data2.getElem(i)));
		}
		ImageIO.write(b1, EXTENSION, fOut);
		FileInputStream fis = new FileInputStream(fOut);
		ByteArrayInputStream bis = Algorithms.createByteArrayIS(fis);
		byte[] res = bis.readAllBytes();
		fis.close();
		f1.delete();
		f2.delete();
		fOut.delete();
		return res;
	}

}
