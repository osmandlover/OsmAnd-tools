package net.osmand.server.api.services;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;

import net.osmand.util.Algorithms;

@Service
public class StorageService {

	private static final String FILE_SEPARATOR = "/";

	protected static final Log LOGGER = LogFactory.getLog(StorageService.class);

	protected static final String LOCAL_STORAGE = "local";

	@Value("${storage.default}")
	private String defaultStorage;

	@Autowired
	private Environment env;

	private Map<String, StorageType> storageProviders = new ConcurrentHashMap<>();

	private List<StorageType> defaultStorageProviders;

	public String getDefaultStorage() {
		getAndInitDefaultStorageProviders();
		return defaultStorage;
	}

	protected List<StorageType> getAndInitDefaultStorageProviders() {
		if (defaultStorageProviders == null) {
			defaultStorage = defaultStorage.trim();
			if (Algorithms.isEmpty(defaultStorage)) {
				defaultStorage = LOCAL_STORAGE;
			}
			String[] split = defaultStorage.split(",");
			List<StorageType> sProviders = new ArrayList<StorageService.StorageType>();
			for (String s : split) {
				sProviders.add(getStorageProviderById(s));
			}
			defaultStorageProviders = sProviders;
		}
		return defaultStorageProviders;
	}

	private StorageType getStorageProviderById(String id) {
		id = id.trim();
		StorageType st = storageProviders.get(id);
		if (st != null) {
			return st;
		}
		if (id.equals(LOCAL_STORAGE)) {
			st = new StorageType();
			st.local = true;
		} else {
			String prefix = "storage.datasource." + id + ".";
			String endpointUrl = env.getProperty(prefix + "endpoint");
			checkNotNull(endpointUrl, "endpoint", id);
			String region = env.getProperty(prefix + "region");
			checkNotNull(region, "endpoint", id);
			String bucket = env.getProperty(prefix + "bucket");
			checkNotNull(region, "bucket", id);
			String accessKey = env.getProperty(prefix + "accesskey");
			String secretKey = env.getProperty(prefix + "secretkey");
			AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard()
					.withEndpointConfiguration(new EndpointConfiguration(endpointUrl, region));
			if (!Algorithms.isEmpty(accessKey)) {
				builder = builder.withCredentials(
						new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)));
			}
			AmazonS3 s3 = builder.build();
			st = new StorageType();
			st.bucket = bucket;
			st.s3Conn = s3;
		}
		storageProviders.put(id, st);
		return st;
	}

	private void checkNotNull(String vl, String name, String id) {
		if(Algorithms.isEmpty(vl)) {
			String msg = String.format("For storage configuration '%s' %s was not specified in application properties", id, name);
			LOGGER.warn(msg);
			throw new IllegalArgumentException(msg);
		}
	}

	public boolean storeLocally() {
		for (StorageType s : getAndInitDefaultStorageProviders()) {
			if (s.local) {
				return true;
			}
		}
		return false;
	}

	public String save(String fld, String fileName, @Valid @NotNull @NotEmpty MultipartFile file) throws IOException {
		for (StorageType s : getAndInitDefaultStorageProviders()) {
			if (!s.local) {
				ObjectMetadata om = new ObjectMetadata();
				s.s3Conn.putObject(s.bucket, fld + FILE_SEPARATOR + fileName, file.getInputStream(), om);
			}
		}		
		return defaultStorage;
	}
	
	public InputStream getFileInputStream(String storage, String fld, String filename) {
		if (!Algorithms.isEmpty(storage)) {
			for (String id : storage.split(",")) {
				StorageType st = getStorageProviderById(id);
				if (st != null && !st.local) {
					S3Object obj = st.s3Conn.getObject(new GetObjectRequest(st.bucket, fld + FILE_SEPARATOR + filename));
					return obj.getObjectContent();
				}
			}
		}
		return null;
	}
	
	public void deleteFile(String storage, String fld, String filename) {
		if (!Algorithms.isEmpty(storage)) {
			for (String id : storage.split(",")) {
				StorageType st = getStorageProviderById(id);
				if (st != null && !st.local) {
					st.s3Conn.deleteObject(st.bucket, fld + FILE_SEPARATOR + filename);
				}
			}
		}
	}

	static class StorageType {
		AmazonS3 s3Conn;
		String bucket;
		boolean local;
	}

	

}