package io.mosip.registration.packetmanager.impl;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.cbeffutil.entity.BIR;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.util.JsonUtils;
import io.mosip.kernel.core.util.exception.JsonProcessingException;
import io.mosip.registration.packetmanager.spi.PacketCreator;
import io.mosip.registration.packetmanager.spi.PacketSigner;
import io.mosip.registration.packetmanager.util.PacketCryptoHelper;
import io.mosip.registration.packetmanager.util.PacketManagerHelper;
import io.mosip.registration.packetmananger.constants.ErrorCode;
import io.mosip.registration.packetmananger.constants.PacketManagerConstants;
import io.mosip.registration.packetmananger.datatype.BiometricsType;
import io.mosip.registration.packetmananger.datatype.DocumentType;
import io.mosip.registration.packetmananger.dto.AuditDto;
import io.mosip.registration.packetmananger.dto.BiometricsDto;
import io.mosip.registration.packetmananger.dto.DocumentDto;
import io.mosip.registration.packetmananger.dto.PacketInfoDto;
import io.mosip.registration.packetmananger.dto.SimpleDto;
import io.mosip.registration.packetmananger.dto.metadata.DocumentMetaInfo;
import io.mosip.registration.packetmananger.dto.metadata.FieldValue;
import io.mosip.registration.packetmananger.dto.metadata.HashSequenceMetaInfo;
import io.mosip.registration.packetmananger.dto.metadata.MetaInfo;
import io.mosip.registration.packetmananger.dto.metadata.ModalityException;
import io.mosip.registration.packetmananger.dto.metadata.ModalityInfo;
import io.mosip.registration.packetmananger.exception.PacketCreatorException;


@Component
public class PacketCreatorImpl implements PacketCreator {	
	
	@Autowired
	private PacketManagerHelper helper;	
	
	@Autowired
	private CbeffBIRBuilder cbeffBIRBuilder;
	
	@Autowired
	private PacketCryptoHelper packetCryptoHelper;
	
	@Value("${mosip.kernel.packetmanager.default-subpacket-name:id}")
	private String defaultSubpacketName;
	
	private PacketInfoDto packetInfoDto = null;	
	
	@Override
	public boolean isPacketCreatorInitialized() {
		return this.packetInfoDto == null ? false : true;
	}	
	
	@Override
	public void initialize() {		
		this.packetInfoDto = new PacketInfoDto();
	}	

	@Override
	public void setField(String fieldName, Object value) {
		this.packetInfoDto.setField(fieldName, value);		
	}

	@Override
	public void setField(String fieldName, List<SimpleDto> value) {
		this.packetInfoDto.setField(fieldName, value);		
	}

	@Override
	public void setBiometric(String fieldName, List<BiometricsDto> value) {
		this.packetInfoDto.setBiometricField(fieldName, value);		
	}

	@Override
	public void setDocument(String fieldName, DocumentDto value) {
		this.packetInfoDto.setDocumentField(fieldName, value);		
	}

	
	@Override
	public void setAudits(List<AuditDto> auditList) {
		this.packetInfoDto.setAudits(auditList);
	}
	
	@Override
	public void setMetaInfo(String label, String value) {
		this.packetInfoDto.setMetaData(label, value);
	}

	@Override
	public void setBiometricException(String fieldName, List<ModalityException> modalityExceptions) {
		this.packetInfoDto.setExceptionBiometrics(fieldName, modalityExceptions);
	}
	
	@Override
	public void setAcknowledgement(String acknowledgeReceiptName, byte[] acknowledgeReceipt) {
		this.packetInfoDto.setAcknowledgeReceipt(acknowledgeReceipt);
		this.packetInfoDto.setAcknowledgeReceiptName(acknowledgeReceiptName);
	}

	@Override
	public byte[] createPacket(String registrationId, double version, String schemaJson, 
			Map<String, String> categoryPacketMapping, byte[] publicKey, PacketSigner signer) throws PacketCreatorException {
		
		if(this.packetInfoDto == null)
			throw new PacketCreatorException(ErrorCode.INITIALIZATION_ERROR.getErrorCode(),	
					ErrorCode.INITIALIZATION_ERROR.getErrorMessage());
		
		Map<String, List<Object>> identityProperties = loadSchemaFields(schemaJson, categoryPacketMapping);
		
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try(ZipOutputStream packetZip = new ZipOutputStream(new BufferedOutputStream(out))) {
			
			for(String subpacketName : identityProperties.keySet()) {
				List<Object> schemaFields = identityProperties.get(subpacketName);
				byte[] subpacketBytes = createSubpacket(version, schemaFields, defaultSubpacketName.equalsIgnoreCase(subpacketName), 
						registrationId);
				
				//TODO sign zip
				subpacketBytes = packetCryptoHelper.encryptPacket(subpacketBytes, publicKey);
				addEntryToZip(String.format(PacketManagerConstants.SUBPACKET_ZIP_FILE_NAME, registrationId, subpacketName), 
						subpacketBytes, packetZip);
			}
			
		} catch (IOException e) {
			throw new PacketCreatorException(ErrorCode.PKT_ZIP_ERROR.getErrorCode(), 
					ErrorCode.PKT_ZIP_ERROR.getErrorMessage().concat(ExceptionUtils.getStackTrace(e)));
		} finally {
			this.packetInfoDto = null;
		}
		
		//TODO sign zip
		return out.toByteArray();
	}
	
	@SuppressWarnings("unchecked")
	private byte[] createSubpacket(double version, List<Object> schemaFields, boolean isDefault, String registrationId) 
			throws PacketCreatorException {		
			
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream subpacketZip = new ZipOutputStream(new BufferedOutputStream(out))) {
			
			Map<String, Object> identity = new HashMap<String, Object>();
			Map<String, HashSequenceMetaInfo> hashSequences = new HashMap<>();
			MetaInfo metaInfo = new MetaInfo();
			
			identity.put(PacketManagerConstants.IDSCHEMA_VERSION, version);			
			metaInfo.addMetaData(new FieldValue(PacketManagerConstants.REGISTRATIONID, registrationId));					
						
			for(Object obj : schemaFields) {
				Map<String, Object> field = (Map<String, Object>) obj;
				String fieldName = (String) field.get(PacketManagerConstants.SCHEMA_ID);
				
				switch ((String) field.get(PacketManagerConstants.SCHEMA_TYPE)) {
				case PacketManagerConstants.BIOMETRICS_TYPE:
					if(this.packetInfoDto.getBiometrics().containsKey(fieldName))					
						addBiometricDetailsToZip(fieldName, identity, metaInfo, subpacketZip, hashSequences);					
					break;					
				case PacketManagerConstants.DOCUMENTS_TYPE:
					if(this.packetInfoDto.getDocuments().containsKey(fieldName))
						addDocumentDetailsToZip(fieldName, identity, metaInfo, subpacketZip, hashSequences);
					break;
				default:
					if(this.packetInfoDto.getDemographics().containsKey(fieldName))
						identity.put(fieldName, this.packetInfoDto.getDemographics().get(fieldName));
					break;
				}
			}
			
			byte[] identityBytes = JsonUtils.javaObjectToJsonString(identity).getBytes();
			addEntryToZip(PacketManagerConstants.IDENTITY_FILENAME, identityBytes, subpacketZip);
			addHashSequenceWithSource(PacketManagerConstants.DEMOGRAPHIC_SEQ, PacketManagerConstants.IDENTITY_FILENAME, identityBytes, 
					hashSequences);			
			addOtherFilesToZip(isDefault, metaInfo, subpacketZip, hashSequences);			
			
		} catch (JsonProcessingException e) {
			throw new PacketCreatorException(ErrorCode.OBJECT_TO_JSON_ERROR.getErrorCode(), 
					ErrorCode.BIR_TO_XML_ERROR.getErrorMessage().concat(ExceptionUtils.getStackTrace(e)));
		} catch (IOException e) {
			throw new PacketCreatorException(ErrorCode.PKT_ZIP_ERROR.getErrorCode(), 
					ErrorCode.PKT_ZIP_ERROR.getErrorMessage().concat(ExceptionUtils.getStackTrace(e)));
		}
		return out.toByteArray();
	}
	
	private void addDocumentDetailsToZip(String fieldName, Map<String, Object> identity, MetaInfo metaInfo, 
			ZipOutputStream zipOutputStream, Map<String, HashSequenceMetaInfo> hashSequences) throws PacketCreatorException {
		DocumentDto dto = this.packetInfoDto.getDocuments().get(fieldName);						
		identity.put(fieldName, new DocumentType(dto.getValue(), dto.getType(), dto.getFormat()));
		String fileName = String.format("%s.%s", dto.getValue(), dto.getFormat());
		addEntryToZip(fileName, dto.getDocument(), zipOutputStream);					
		metaInfo.addDocumentMetaInfo(new DocumentMetaInfo(dto.getValue(), dto.getCategory(), 
				dto.getOwner(), dto.getType()));
		
		addHashSequenceWithSource(PacketManagerConstants.DEMOGRAPHIC_SEQ, dto.getValue(), dto.getDocument(), 
				hashSequences);
	}
	
	private void addBiometricDetailsToZip(String fieldName, Map<String, Object> identity, MetaInfo metaInfo, 
			ZipOutputStream zipOutputStream, Map<String, HashSequenceMetaInfo> hashSequences) throws PacketCreatorException {
		List<BIR> birs = getSerializedBiometrics(this.packetInfoDto.getBiometrics().get(fieldName), metaInfo);						
		if(!birs.isEmpty()) {
			byte[] xmlBytes;
			try {
				xmlBytes = helper.getXMLData(birs);
			} catch (Exception e) {
				throw new PacketCreatorException(ErrorCode.BIR_TO_XML_ERROR.getErrorCode(), 
						ErrorCode.BIR_TO_XML_ERROR.getErrorMessage().concat(ExceptionUtils.getStackTrace(e)));
			}
			addEntryToZip(String.format(PacketManagerConstants.CBEFF_FILENAME_WITH_EXT, fieldName), xmlBytes, zipOutputStream);							
			identity.put(fieldName, new BiometricsType(PacketManagerConstants.CBEFF_FILE_FORMAT, 
					PacketManagerConstants.CBEFF_VERSION, String.format(PacketManagerConstants.CBEFF_FILENAME, fieldName)));
			
			addHashSequenceWithSource(PacketManagerConstants.BIOMETRIC_SEQ, String.format(PacketManagerConstants.CBEFF_FILENAME, 
					fieldName), xmlBytes, hashSequences);
		}						
		if(this.packetInfoDto.getExceptionBiometrics().containsKey(fieldName))
			metaInfo.setBiometricException(this.packetInfoDto.getExceptionBiometrics().get(fieldName));
	}
	
	private void addHashSequenceWithSource(String sequenceType, String name, byte[] bytes, 
			Map<String, HashSequenceMetaInfo> hashSequences) {
		if(!hashSequences.containsKey(sequenceType))
			hashSequences.put(sequenceType, new HashSequenceMetaInfo(sequenceType));		
		
		hashSequences.get(sequenceType).addHashSource(name, bytes);
	}
	
	//TODO - check if ACK files need to added ? if yes then add it in packet and also in hash sequence
	private void addOtherFilesToZip(boolean isDefault, MetaInfo metaInfo, ZipOutputStream zipOutputStream, 
			Map<String, HashSequenceMetaInfo> hashSequences) throws JsonProcessingException, PacketCreatorException {
		
		if(isDefault) {  
			fillAllMetaInfo(metaInfo);
			addOperationsBiometricsToZip(this.packetInfoDto.getOfficerBiometrics(), PacketManagerConstants.OFFICER, 
					zipOutputStream, metaInfo, hashSequences);
			addOperationsBiometricsToZip(this.packetInfoDto.getSupervisorBiometrics(), PacketManagerConstants.SUPERVISOR, 
					zipOutputStream, metaInfo, hashSequences);
			
			if(this.packetInfoDto.getAudits() == null || this.packetInfoDto.getAudits().isEmpty())
				throw new PacketCreatorException(ErrorCode.AUDITS_REQUIRED.getErrorCode(), ErrorCode.AUDITS_REQUIRED.getErrorMessage());
			
			byte[] auditBytes = JsonUtils.javaObjectToJsonString(this.packetInfoDto.getAudits()).getBytes();			
			addEntryToZip(PacketManagerConstants.AUDIT_FILENAME, auditBytes, zipOutputStream);
			addHashSequenceWithSource(PacketManagerConstants.OPERATIONS_SEQ, PacketManagerConstants.AUDIT_FILENAME, auditBytes, 
					hashSequences);
			
			HashSequenceMetaInfo hashSequenceMetaInfo = hashSequences.get(PacketManagerConstants.OPERATIONS_SEQ);
			addEntryToZip(PacketManagerConstants.PACKET_OPER_HASH_FILENAME, 
					helper.generateHash(hashSequenceMetaInfo.getValue(), hashSequenceMetaInfo.getHashSource()),
					zipOutputStream);
			metaInfo.addHashSequence2(hashSequenceMetaInfo);
		}
		
		addPacketDataHash(hashSequences, metaInfo, zipOutputStream);	
		
		addEntryToZip(PacketManagerConstants.PACKET_META_FILENAME, 
				JsonUtils.javaObjectToJsonString(metaInfo).getBytes(), zipOutputStream);
	}
	
	private void addPacketDataHash(Map<String, HashSequenceMetaInfo> hashSequences, MetaInfo metaInfo,
			ZipOutputStream zipOutputStream) throws PacketCreatorException  {
		
		LinkedList<String> sequence = new LinkedList<String>();
		Map<String, byte[]> data = new HashMap<>();
		if(hashSequences.containsKey(PacketManagerConstants.BIOMETRIC_SEQ)) {
			sequence.addAll(hashSequences.get(PacketManagerConstants.BIOMETRIC_SEQ).getValue());
			data.putAll(hashSequences.get(PacketManagerConstants.BIOMETRIC_SEQ).getHashSource());
			metaInfo.addHashSequence1(hashSequences.get(PacketManagerConstants.BIOMETRIC_SEQ));
		}		
		if(hashSequences.containsKey(PacketManagerConstants.DEMOGRAPHIC_SEQ)) {
			sequence.addAll(hashSequences.get(PacketManagerConstants.DEMOGRAPHIC_SEQ).getValue());
			data.putAll(hashSequences.get(PacketManagerConstants.DEMOGRAPHIC_SEQ).getHashSource());
			metaInfo.addHashSequence1(hashSequences.get(PacketManagerConstants.DEMOGRAPHIC_SEQ));
		}
		
		addEntryToZip(PacketManagerConstants.PACKET_DATA_HASH_FILENAME, helper.generateHash(sequence, data),
				zipOutputStream);		
	}
	
	
	private void fillAllMetaInfo(MetaInfo metaInfo) {
		if(this.packetInfoDto.getMetaData() != null) {
			for(FieldValue fieldValue : this.packetInfoDto.getMetaData()) {
				metaInfo.addMetaData(fieldValue);
			}
		}
		
		if(this.packetInfoDto.getOperationsData() != null) {
			for(FieldValue fieldValue : this.packetInfoDto.getOperationsData()) {
				metaInfo.addOperationsData(fieldValue);
			}
		}
		
		metaInfo.setCapturedRegisteredDevices(this.packetInfoDto.getCapturedRegisteredDevices());
		metaInfo.setCapturedNonRegisteredDevices(this.packetInfoDto.getCapturedNonRegisteredDevices());
		metaInfo.setCheckSum(this.packetInfoDto.getCheckSum());		
		metaInfo.setPrintingName(this.packetInfoDto.getPrintingName());			
	}
	
	private void addOperationsBiometricsToZip(List<BiometricsDto> list, String operationType, 
			ZipOutputStream zipOutputStream, MetaInfo metaInfo, Map<String, HashSequenceMetaInfo> hashSequences) throws PacketCreatorException {
		if(list != null && !list.isEmpty()) {	
			List<BIR> birs = new ArrayList<BIR>();			
			for(BiometricsDto bioDto : list) {
				BIR bir = cbeffBIRBuilder.buildBIR(bioDto.getModalityISO(), bioDto.getFormatType(), bioDto.getQualityScore(), 
						bioDto.getType(), bioDto.getSubType());			
				birs.add(bir);
			}
			
			byte[] xmlBytes;
			try {
				xmlBytes = helper.getXMLData(birs);
			} catch (Exception e) {
				throw new PacketCreatorException(ErrorCode.BIR_TO_XML_ERROR.getErrorCode(), 
						ErrorCode.BIR_TO_XML_ERROR.getErrorMessage().concat(ExceptionUtils.getStackTrace(e)));
			}
			
			String fileName = String.format(PacketManagerConstants.CBEFF_FILENAME_WITH_EXT, operationType);
			addEntryToZip(fileName, xmlBytes, zipOutputStream);
			metaInfo.addOperationsData(new FieldValue(String.format("%sBiometricFileName", operationType), fileName));
			addHashSequenceWithSource(PacketManagerConstants.OPERATIONS_SEQ, String.format(PacketManagerConstants.CBEFF_FILENAME,
					operationType), xmlBytes, hashSequences);
		}
	}	
	
	private Map<String, List<Object>> loadSchemaFields(String schemaJson, Map<String, String> mappingList) throws PacketCreatorException {		
		Map<String, List<Object>> packetBasedMap = new HashMap<String, List<Object>>();
		
		try {
			JSONObject schema = new JSONObject(schemaJson);
			schema =  schema.getJSONObject(PacketManagerConstants.PROPERTIES);
			schema =  schema.getJSONObject(PacketManagerConstants.IDENTITY);
			schema =  schema.getJSONObject(PacketManagerConstants.PROPERTIES);
			
			JSONArray fieldNames = schema.names();
			for(int i=0;i<fieldNames.length();i++) {
				String fieldName = fieldNames.getString(i);
				JSONObject fieldDetail = schema.getJSONObject(fieldName);
				String fieldCategory = fieldDetail.has(PacketManagerConstants.SCHEMA_CATEGORY) ? 
						fieldDetail.getString(PacketManagerConstants.SCHEMA_CATEGORY) : "none";			
				String packetName = mappingList.get(fieldCategory.toLowerCase());
				
				if(!packetBasedMap.containsKey(packetName)) {				
					packetBasedMap.put(packetName, new ArrayList<Object>());
				}
				
				Map<String, String> attributes = new HashMap<>();
				attributes.put(PacketManagerConstants.SCHEMA_ID, fieldName);
				attributes.put(PacketManagerConstants.SCHEMA_TYPE, fieldDetail.has(PacketManagerConstants.SCHEMA_REF) ? 
						fieldDetail.getString(PacketManagerConstants.SCHEMA_REF) : fieldDetail.getString(PacketManagerConstants.SCHEMA_TYPE));
				packetBasedMap.get(packetName).add(attributes);
			}
		} catch (JSONException e) {
			throw new PacketCreatorException(ErrorCode.JSON_PARSE_ERROR.getErrorCode(), 
						ErrorCode.JSON_PARSE_ERROR.getErrorMessage().concat(ExceptionUtils.getStackTrace(e)));
		}
		return packetBasedMap;		
	}
	
	
	public List<BIR> getSerializedBiometrics(List<BiometricsDto> list, MetaInfo metaInfo) {		
		List<BIR> birs = new ArrayList<BIR>();			
		for(BiometricsDto bioDto : list) {
			BIR bir = cbeffBIRBuilder.buildBIR(bioDto.getModalityISO(), bioDto.getFormatType(), bioDto.getQualityScore(), 
					bioDto.getType(), bioDto.getSubType());			
			birs.add(bir);
			
			metaInfo.setBiometrics(bioDto.getSubType(), bioDto.getModalityName(), 
					new ModalityInfo(bir.getBdbInfo().getIndex(), bioDto.getNumOfRetries(), bioDto.isForceCaptured()));
		}
		return birs;		
	}
	
	
	private void addEntryToZip(String fileName, byte[] data, ZipOutputStream zipOutputStream) 
			throws PacketCreatorException {		
		try {			
			if(data != null) {
				ZipEntry zipEntry = new ZipEntry(fileName);
				zipOutputStream.putNextEntry(zipEntry);
				zipOutputStream.write(data);
			}			
		} catch (IOException e) {
			throw new PacketCreatorException(ErrorCode.ADD_ZIP_ENTRY_ERROR.getErrorCode(), 
					ErrorCode.ADD_ZIP_ENTRY_ERROR.getErrorMessage().concat(ExceptionUtils.getStackTrace(e)));
		}		
	}	

}