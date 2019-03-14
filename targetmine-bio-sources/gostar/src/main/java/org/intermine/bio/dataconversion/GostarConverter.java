package org.intermine.bio.dataconversion;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.intermine.bio.dataconversion.GostarFileParser;
import org.intermine.dataconversion.ItemWriter;
import org.intermine.metadata.Model;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.sql.Database;
import org.intermine.util.FormattedTextParser;
import org.intermine.xml.full.Item;
/**
 * 
 * @author Ishikawa.Motokazu
 */
public class GostarConverter extends BioFileConverter {
	private static final Logger LOG = Logger.getLogger(GostarConverter.class);
	
	private static final String DATASET_TITLE = "GOSTAR";
	private static final String DATA_SOURCE_NAME = "GOSTAR";
	
	private File activityAssayFile;
	private File allActivityGostarFile;
	private File bindingSiteFile;
	private File casFile;
	private File compoundSynonymsFile;
	private File referenceMasterFile;
	private File structureDetailsFile;
	private File structureDetailsInchiInfoFile;
	private File targetProteinMasterFile;
	
	// key is assay_id, value is activity Item's identifier
	private Map<String, List<String>> assayMap = new HashMap<String, List<String>>();
	// key is gvk_id, value is binding site
	private Map<String, String> bindingSiteMap = new HashMap<String, String>();
	// key is gvk_id, value is cas_no
	private Map<String, String> casMap = new HashMap<String, String>();
	// key is gvk_id, value is compound Item's identifier
	private Map<String, String> compoundMap = new HashMap<String, String>();
	// key is inchikey, value is compoundGroup Item's identifier
	private Map<String, String> compoundGroupMap = new HashMap<String, String>();
	// key is str_id, value is compound Item's identifier
	private Map<String, List<String>> compoundSynonymMap = new HashMap<String, List<String>>();
	// key is gvk id, value is inchikey
	private Map<String, String> inchikeyMap = new HashMap<String, String>();
	// key is intId(gvkId_targetId), value is GostarInteraction Item's identifier
	private Map<String, String> interactionMap = new HashMap<String, String>();
	// key is uniprotId, value is protein Item's identifier
	private Map<String, String> proteinMap = new HashMap<String, String>();
	// key is publication, value is publication Item's identifier
	private Map<String, String> publicationMap = new HashMap<String, String>();
	// key is ref_id, value is pubmed_id
	private Map<String, String> refPubmedMap = new HashMap<String, String>();
	// key is target_id, value is protein Item's identifier
	private Map<String, String> targetProteinMap = new HashMap<String, String>();

	/**
	 * Construct a new GostarConverter.
	 * 
	 * @param model
	 *            the Model used by the object store we will write to with the ItemWriter
	 * @param writer
	 *            an ItemWriter used to handle Items created
	 */
	public GostarConverter(ItemWriter writer, Model model) {
		super(writer, model, DATA_SOURCE_NAME, DATASET_TITLE);
	}

	
	/**
	 * {@inheritDoc}
	 */
	public void process(Reader reader) throws Exception {

		/**
		 * Counting how many items imported
		 */
		int countActivity = 0;
		int countAssay = 0;
		int countSynonym = 0;
		int countCompound = 0;
		
		/**
		 * Processing CAS.csv to collect cas number
		 */
		Iterator<String[]> casIterator = getCasIterator();
		casIterator.next(); // Skip header
		while( casIterator.hasNext() ) {
			String[] casRow = casIterator.next();
			String casNo = casRow[0];
			String gvkId = casRow[1];
			casMap.put( gvkId, casNo );
		}
		
		/**
		 * Processing COMPOUND_SYNONYMS.csv to collect synonyms
		 */
		Iterator<String[]> compoundSynonymsIterator = getCompoundSynonymsIterator();
		compoundSynonymsIterator.next(); // Skip header
		while( compoundSynonymsIterator.hasNext() ) {
			String[] compoundSynonymsRow = compoundSynonymsIterator.next();
			String synonym = compoundSynonymsRow[0];
			String strId = compoundSynonymsRow[3];
			setSynonym( strId, synonym );
			countSynonym += 1;
		}
		
		/**
		 * Processing REFERENCE_MASTER.csv to collect ref_id-pubmed_id relationship
		 */
		Iterator<String[]> referenceMasterIterator = getReferenceMasterIterator();
		referenceMasterIterator.next(); // Skip header
		while( referenceMasterIterator.hasNext() ) {
			String[] referenceMasterRow = referenceMasterIterator.next();
			String pubmedId = referenceMasterRow[5];
			String refId = referenceMasterRow[9];
			refPubmedMap.put( refId, pubmedId );
		}
		
		/**
		 * Processing TARGET_PROTEIN_MASTER.csv to collect protein information
		 */
		Iterator<String[]> targetProteinMasterIterator = getTargetProteinMasterIterator();
		targetProteinMasterIterator.next(); // Skip header
		while( targetProteinMasterIterator.hasNext() ) {
			
			String[] targetProteinMasterRow = targetProteinMasterIterator.next();
			String targetId = targetProteinMasterRow[4];
			String uniprotId = targetProteinMasterRow[9];
			registerTarget( targetId, uniprotId );
			
		}
		
		/**
		 * Processing  STRUCTURE_DETAILS_INCHI_INFO.csv to collect compound inchikey information
		 */
		Iterator<String[]> structureDetailsInchiInfoIterator = getStructureDetailsInchiInfoIterator();
		structureDetailsInchiInfoIterator.hasNext(); // Skip header
		while( structureDetailsInchiInfoIterator.hasNext() ) {
			
			String[] structureDetailsInchiInfoRow = structureDetailsInchiInfoIterator.next();
			if( structureDetailsInchiInfoRow.length < 4 ) {
				continue;
			}
			String gvkId = structureDetailsInchiInfoRow[0];
			String iupacInchiKey = structureDetailsInchiInfoRow[3].replace( "InChIKey=", "" );
			
			inchikeyMap.put( gvkId, iupacInchiKey);
		}
		
		/**
		 * Processing  STRUCTURE_DETAILS.csv to collect compound information
		 */
		Iterator<String[]> structureDetailsIterator = getStructureDetailsIterator();
		structureDetailsIterator.next(); // Skip header
		while( structureDetailsIterator.hasNext() ) {
			String[] structureDetailsRow = structureDetailsIterator.next();

			if( structureDetailsRow.length < 12 ){
				continue;
			}
			
			String compoundName = structureDetailsRow[0];
			String gvkId = structureDetailsRow[2];
			String iupacName = structureDetailsRow[3];
			String subSmiles = structureDetailsRow[7];
			if( ! inchikeyMap.containsKey( gvkId ) ) {
				continue;
			}
			String inchikey = inchikeyMap.get( gvkId );
			String strId = structureDetailsRow[11];
			
			String casRegistryNumber = null;
			String compoundStructureRef = null;
			
			if( !"".equals(subSmiles) ) {
				compoundStructureRef = getCompoundStructure( "SMILES", subSmiles );
			}
			
			if( casMap.containsKey(gvkId) ) {
				casRegistryNumber = casMap.get(gvkId);
			}
			String compoundGroupRef = null;
			int indexof = inchikey.indexOf("-");
			if( 0 <= indexof ){
				String compoundGroupId = inchikey.substring(0, indexof );
				if (compoundGroupId.length() == 14) {
					compoundGroupRef = getCompoundGroup(compoundGroupId, compoundName);
				} else {
					LOG.error(String.format("Bad InChIKey value: %s .", inchikey));
				}
			}
			setCompound( gvkId, compoundName, inchikey, casRegistryNumber, iupacName, compoundStructureRef, compoundSynonymMap.get( strId ), compoundGroupRef );
			countCompound += 1;
		}
		
		/**
		 * Processing BINDING_SITE.csv to collect activity information
		 */
		Iterator<String[]> bindingSiteIterator = getBindingSiteIterator();
		bindingSiteIterator.next(); // Skip header
		while( bindingSiteIterator.hasNext() ) {
			String[] bindingSiteRow = bindingSiteIterator.next();
			String gvkId = bindingSiteRow[0];
			String bindingSite = bindingSiteRow[1];
			bindingSiteMap.put(gvkId, bindingSite);
		}
		
		/**
		 * Processing ALL_ACTIVITY_GOSTAR.csv to collect activity information
		 */
		Iterator<String[]> allActivityGostarIterator = getAllActivityGostarIterator();
		allActivityGostarIterator.next(); // Skip header
		while( allActivityGostarIterator.hasNext() ) {
			String[] allActivityGostarRow = allActivityGostarIterator.next();
			if( allActivityGostarRow.length < 34 ){
				continue;
			}
			String type = allActivityGostarRow[3];
			String unit = allActivityGostarRow[4];
			String conc = allActivityGostarRow[5];
			String assayId = allActivityGostarRow[6];
			String gvkId = allActivityGostarRow[14];
			String refId = allActivityGostarRow[25];
			String targetId = allActivityGostarRow[33];
			
			if( ! targetProteinMap.containsKey(targetId) ){
				
				// Skip this interaction because no uniprot id for this target
				continue;
				
			}else if( type != "IC50" && type != "Kd" && type != "Ki" && type != "EC50" && type != "AC50" ){
				
				// Skip this interaction because measurement type is not what we want
				continue;
				
			}else if( unit != "nM" ) {
				
				// Skip this interaction because unit is not what we want
				continue;
				
			}
			setActivity( gvkId, targetId, type, conc, refId, assayId );
			countActivity += 1;
		}
		
		/**
		 * Processing ACTIVITY_ASSAY.csv to collect assay information
		 */
		Iterator<String[]> activityAssayIterator = getActivityAssayIterator();
		activityAssayIterator.next(); // Skip header
		while( activityAssayIterator.hasNext() ) {
			
			String[] activityAssayRow = activityAssayIterator.next();
			if( activityAssayRow.length < 11 ){
				continue;
			}
			String enzymeCellAssay = activityAssayRow[4];
			String assayId = activityAssayRow[6];
			String activity = activityAssayRow[10];
			
			if( ! assayMap.containsKey( assayId ) ) {
				
				// Skip because this assay has not been related to stored activity
				continue;
				
			}
			
			setAssay( assayId, enzymeCellAssay, activity );
			countAssay += 1;
		}
		
		/**
		 * Output how many items have been imported
		 */
		LOG.info( "GOSTAR: number of activities imported="+countActivity );
		LOG.info( "GOSTAR: number of assay imported="+countAssay );
		LOG.info( "GOSTAR: number of synonym imported="+countSynonym );
		LOG.info( "GOSTAR: number of compound imported="+countCompound );
	}

	private void registerTarget( String targetId, String uniprotId ) throws ObjectStoreException {
		
		if ( !"".equals(targetId) && !"".equals(uniprotId) ) {
			targetProteinMap.put( targetId, getProtein( uniprotId ) );
		}
		
	}
	
	private void setActivity( String gvkId, String targetId, String type, String conc, String refId, String assayId ) throws ObjectStoreException {
		
		String interactionRef = getInteraction( gvkId, targetId );
		Item item = createItem( "Activity" );
		item.setAttribute( "type", type );
		item.setAttribute( "conc", conc );
		item.setReference( "interaction", interactionRef );
		if( refId != null && refId != "" && refPubmedMap.containsKey( refId ) ) {
			item.addToCollection( "publications", publicationMap.get( refPubmedMap.get( refId ) ) );
		}
		store( item );
		
		if( ! assayMap.containsKey( assayId ) ) {
			assayMap.put( assayId, new ArrayList<String>() );
		}
		
		assayMap.get( assayId ).add( item.getIdentifier() );
		
	}
	
	private void setAssay( String assayId, String enzymeCellAssay, String activity ) throws ObjectStoreException {
		
		Item item = createItem( "CompoundProteinInteractionAssay" );
		item.setAttribute( "identifier", assayId );
		item.setAttribute( "originalId", assayId );
		item.setAttribute( "name", enzymeCellAssay );
		item.setAttribute( "assayType", activity );
		item.setAttribute( "source", "GOSTAR" );
		store( item );
		
		
	}
	
	private String setCompound(
			String gvkId,
			String compoundName,
			String inchikey,
			String casRegistryNumber,
			String iupacName,
			String compoundStructureRef,
			List<String> synonymList,
			String compoundGroupRef ) throws ObjectStoreException {

		String ret = compoundMap.get(gvkId);
		if (ret == null) {
			Item item = createItem( "GostarCompound" );
			item.setAttribute( "identifier", String.format("GOSTAR:%s", gvkId) );
			item.setAttribute( "originalId", gvkId );
			if( null != compoundName && ! "".equals( compoundName ) ){
				item.setAttribute( "name", compoundName );
			}
			if( null != inchikey && !"".equals( inchikey ) ){
				item.setAttribute( "inchiKey", inchikey );
			}
			if( null != casRegistryNumber && !"".equals( casRegistryNumber ) ) {
				item.setAttribute( "casRegistryNumber", casRegistryNumber );
			}
			if( null != iupacName && !"".equals( iupacName ) ){
				item.setAttribute( "iupacName", iupacName );
			}
			if( null != compoundStructureRef && !"".equals(compoundStructureRef) ) {
				item.addToCollection( "structures", compoundStructureRef );
			}
			if( null != synonymList ) {
				for( String synonym : synonymList ) {
					item.addToCollection( "synonyms", getSynonym( synonym ) );
				}
			}
			if( null != compoundGroupRef ){
				item.setReference( "compoundGroup", compoundGroupRef );
			}
			store(item);
			ret = item.getIdentifier();
			compoundMap.put( gvkId, ret );
		}
		return ret;
	}
	
	private String getPublication( String pubmedId ) throws ObjectStoreException {
		
		String ref = publicationMap.get( pubmedId );
		if( ref == null ) {
			Item item = createItem("Publication");
			item.setAttribute( "pubMedId", pubmedId );
			store( item );
			publicationMap.put( pubmedId, item.getIdentifier() );
		}
		return ref;
		
	}
	
	private String getSynonym( String synonym ) throws ObjectStoreException  {
		Item item = createItem( "CompoundSynonym" );
		item.setAttribute( "value", synonym );
		store( item );
		return item.getIdentifier();
	}
	
	private void setSynonym(String strId, String synonym) {
		
		if ( ! compoundSynonymMap.containsKey( strId ) ) {
			compoundSynonymMap.put( strId, new ArrayList<String>() );
		}
		compoundSynonymMap.get( strId ).add( synonym );
	}
	
	private String getCompoundGroup(String inchiKey, String name) throws ObjectStoreException {
		String ret = compoundGroupMap.get(inchiKey);
		if (ret == null) {
			Item item = createItem("CompoundGroup");
			item.setAttribute("identifier", inchiKey);
			if( null != name && !"".equals( name ) ){
				item.setAttribute("name", name);
			}
			store(item);
			ret = item.getIdentifier();
			compoundGroupMap.put(inchiKey, ret);
		}
		return ret;
	}
	
	private String getCompoundStructure(String type, String value) throws ObjectStoreException {
		Item item = createItem( "CompoundStructure" );
		item.setAttribute( "type", type );
		item.setAttribute( "value", value );
		store(item);
		return item.getIdentifier();
	}
	
	private String getProtein( String uniprotId ) throws ObjectStoreException {
		
		String ret = proteinMap.get(uniprotId);
		if (ret == null) {
			Item item = createItem("Protein");
			item.setAttribute("primaryAccession", uniprotId);
			store(item);
			ret = item.getIdentifier();
			proteinMap.put(uniprotId, ret);
		}
		return ret;
		
	}
	
	private String getInteraction( String gvkId, String targetId ) throws ObjectStoreException {
		
		String intId = gvkId + "_" + targetId;
		String interactionRef = interactionMap.get(intId);
		if ( interactionRef == null ){
			Item item = createItem("GostarInteraction");
			item.setReference( "compound", compoundMap.get(gvkId) );
			item.setReference( "protein", targetProteinMap.get(targetId) );
			if( bindingSiteMap.containsKey(gvkId) ){
				item.setAttribute( "bindingSite", bindingSiteMap.get(gvkId) );
			}
		}
		return interactionRef;
		
	}
	
	public String getDataSetTitle(String taxonId) {
		return DATASET_TITLE;
	}
	
	private Iterator<String[]> getActivityAssayIterator() throws IOException {
		return FormattedTextParser.parseCsvDelimitedReader( new FileReader( this.activityAssayFile ) );
	}
	
	public void setActivityAssayFile(File activityAssayFile) {
		this.activityAssayFile = activityAssayFile;
	}
	
	private Iterator<String[]> getAllActivityGostarIterator() throws IOException {
		return FormattedTextParser.parseCsvDelimitedReader( new FileReader( this.allActivityGostarFile ) );
	}
	
	public void setAllActivityGostarFile(File allActivityGostarFile) {
		this.allActivityGostarFile = allActivityGostarFile;
	}
	
	private Iterator<String[]> getBindingSiteIterator() throws IOException {
		return FormattedTextParser.parseCsvDelimitedReader( new FileReader( this.bindingSiteFile ) );
	}
	
	public void setBindingSiteFile(File bindingSiteFile) {
		this.bindingSiteFile = bindingSiteFile;
	}
	
	private Iterator<String[]> getCasIterator() throws IOException {
		return FormattedTextParser.parseCsvDelimitedReader( new FileReader( this.casFile ) );
	}
	
	public void setCasFile(File casFile) {
		this.casFile = casFile;
	}
	
	private Iterator<String[]> getCompoundSynonymsIterator() throws IOException {
		return FormattedTextParser.parseCsvDelimitedReader( new FileReader( this.compoundSynonymsFile ) );
	}
	
	public void setCompoundSynonymsFile(File compoundSynonymsFile) {
		this.compoundSynonymsFile = compoundSynonymsFile;
	}
	
	private Iterator<String[]> getReferenceMasterIterator() throws IOException {
		return FormattedTextParser.parseCsvDelimitedReader( new FileReader( this.referenceMasterFile ) );
	}
	
	public void setReferenceMasterFile(File referenceMasterFile) {
		this.referenceMasterFile = referenceMasterFile;
	}
	
	private Iterator<String[]> getStructureDetailsIterator() throws IOException {
		return GostarFileParser.parseCsvDelimitedReader( new BufferedReader( new FileReader( this.structureDetailsFile ) ) );
	}
	
	public void setStructureDetailsFile(File structureDetailsFile) {
		this.structureDetailsFile = structureDetailsFile;
	}
	
	private Iterator<String[]> getStructureDetailsInchiInfoIterator() throws IOException {
		return FormattedTextParser.parseCsvDelimitedReader( new FileReader( this.structureDetailsInchiInfoFile ) );
	}
	
	public void setStructureDetailsInchiInfoFile(File structureDetailsInchiInfoFile) {
		this.structureDetailsInchiInfoFile = structureDetailsInchiInfoFile;
	}
	
	private Iterator<String[]> getTargetProteinMasterIterator() throws IOException {
		return FormattedTextParser.parseCsvDelimitedReader( new FileReader( this.targetProteinMasterFile ) );
	}
	
	public void setTargetProteinMasterFile(File targetProteinMasterFile) {
		this.targetProteinMasterFile = targetProteinMasterFile;
	}
}
