package gov.hhs.cms.bluebutton.server.app.stu3.providers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Coverage;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import gov.hhs.cms.bluebutton.data.model.rif.Beneficiary;
import gov.hhs.cms.bluebutton.data.model.rif.samples.StaticRifResourceGroup;
import gov.hhs.cms.bluebutton.server.app.ServerTestUtils;

/**
 * Integration tests for {@link CoverageResourceProvider}.
 */
public final class CoverageResourceProviderIT {
	/**
	 * Verifies that
	 * {@link CoverageResourceProvider#read(org.hl7.fhir.dstu3.model.IdType)}
	 * works as expected for {@link Beneficiary}-derived {@link Coverage}s that
	 * do exist in the DB.
	 * 
	 * @throws FHIRException
	 *             (indicates test failure)
	 */
	@Test
	public void readCoveragesForExistingBeneficiary() throws FHIRException {
		List<Object> loadedRecords = ServerTestUtils
				.loadData(Arrays.asList(StaticRifResourceGroup.SAMPLE_A.getResources()));
		IGenericClient fhirClient = ServerTestUtils.createFhirClient();

		Beneficiary beneficiary = loadedRecords.stream().filter(r -> r instanceof Beneficiary).map(r -> (Beneficiary) r)
				.findFirst().get();

		Coverage partACoverage = fhirClient.read().resource(Coverage.class)
				.withId(TransformerUtils.buildCoverageId(MedicareSegment.PART_A, beneficiary)).execute();
		CoverageTransformerTest.assertPartAMatches(beneficiary, partACoverage);

		Coverage partBCoverage = fhirClient.read().resource(Coverage.class)
				.withId(TransformerUtils.buildCoverageId(MedicareSegment.PART_B, beneficiary)).execute();
		CoverageTransformerTest.assertPartBMatches(beneficiary, partBCoverage);

		Coverage partDCoverage = fhirClient.read().resource(Coverage.class)
				.withId(TransformerUtils.buildCoverageId(MedicareSegment.PART_D, beneficiary)).execute();
		CoverageTransformerTest.assertPartDMatches(beneficiary, partDCoverage);
	}

	/**
	 * Verifies that
	 * {@link CoverageResourceProvider#read(org.hl7.fhir.dstu3.model.IdType)}
	 * works as expected for {@link Beneficiary}-derived {@link Coverage}s that
	 * do not exist in the DB.
	 */
	@Test
	public void readCoveragesForMissingBeneficiary() {
		IGenericClient fhirClient = ServerTestUtils.createFhirClient();

		// No data is loaded, so these should return nothing.
		ResourceNotFoundException exception;

		exception = null;
		try {
			fhirClient.read().resource(Coverage.class)
					.withId(TransformerUtils.buildCoverageId(MedicareSegment.PART_A, "1234")).execute();
		} catch (ResourceNotFoundException e) {
			exception = e;
		}
		Assert.assertNotNull(exception);

		exception = null;
		try {
			fhirClient.read().resource(Coverage.class)
					.withId(TransformerUtils.buildCoverageId(MedicareSegment.PART_B, "1234")).execute();
		} catch (ResourceNotFoundException e) {
			exception = e;
		}
		Assert.assertNotNull(exception);

		exception = null;
		try {
			fhirClient.read().resource(Coverage.class)
					.withId(TransformerUtils.buildCoverageId(MedicareSegment.PART_D, "1234")).execute();
		} catch (ResourceNotFoundException e) {
			exception = e;
		}
		Assert.assertNotNull(exception);
	}

	/**
	 * Verifies that
	 * {@link CoverageResourceProvider#searchByBeneficiary(ca.uhn.fhir.rest.param.ReferenceParam)}
	 * works as expected for a {@link Beneficiary} that does exist in the DB.
	 * 
	 * @throws FHIRException
	 *             (indicates test failure)
	 */
	@Test
	public void searchByExistingBeneficiary() throws FHIRException {
		List<Object> loadedRecords = ServerTestUtils
				.loadData(Arrays.asList(StaticRifResourceGroup.SAMPLE_A.getResources()));
		IGenericClient fhirClient = ServerTestUtils.createFhirClient();

		Beneficiary beneficiary = loadedRecords.stream().filter(r -> r instanceof Beneficiary).map(r -> (Beneficiary) r)
				.findFirst().get();
		Bundle searchResults = fhirClient.search().forResource(Coverage.class)
				.where(Coverage.BENEFICIARY.hasId(TransformerUtils.buildPatientId(beneficiary)))
				.returnBundle(Bundle.class).execute();

		Assert.assertNotNull(searchResults);
		Assert.assertEquals(MedicareSegment.values().length, searchResults.getTotal());

		/*
		 * Verify that each of the expected Coverages (one for every
		 * MedicareSegment) is present and looks correct.
		 */

		Coverage partACoverageFromSearchResult = (Coverage) searchResults.getEntry().stream()
				.filter(e -> e.getResource() instanceof Coverage).map(e -> (Coverage) e.getResource())
				.filter(c -> TransformerConstants.COVERAGE_PLAN_PART_A.equals(c.getGrouping().getSubPlan())).findFirst()
				.get();
		CoverageTransformerTest.assertPartAMatches(beneficiary, partACoverageFromSearchResult);

		Coverage partBCoverageFromSearchResult = (Coverage) searchResults.getEntry().stream()
				.filter(e -> e.getResource() instanceof Coverage).map(e -> (Coverage) e.getResource())
				.filter(c -> TransformerConstants.COVERAGE_PLAN_PART_B.equals(c.getGrouping().getSubPlan())).findFirst()
				.get();
		CoverageTransformerTest.assertPartBMatches(beneficiary, partBCoverageFromSearchResult);

		Coverage partDCoverageFromSearchResult = (Coverage) searchResults.getEntry().stream()
				.filter(e -> e.getResource() instanceof Coverage).map(e -> (Coverage) e.getResource())
				.filter(c -> TransformerConstants.COVERAGE_PLAN_PART_D.equals(c.getGrouping().getSubPlan())).findFirst()
				.get();
		CoverageTransformerTest.assertPartDMatches(beneficiary, partDCoverageFromSearchResult);
	}

	/**
	 * Verifies that
	 * {@link CoverageResourceProvider#searchByBeneficiary(ca.uhn.fhir.rest.param.ReferenceParam)}
	 * works as expected for a {@link Beneficiary} that does exist in the DB, with
	 * paging.
	 * 
	 * @throws FHIRException
	 *             (indicates test failure)
	 */
	@Test
	public void searchByExistingBeneficiaryWithPaging() throws FHIRException {
		List<Object> loadedRecords = ServerTestUtils
				.loadData(Arrays.asList(StaticRifResourceGroup.SAMPLE_A.getResources()));
		IGenericClient fhirClient = ServerTestUtils.createFhirClient();

		Beneficiary beneficiary = loadedRecords.stream().filter(r -> r instanceof Beneficiary).map(r -> (Beneficiary) r)
				.findFirst().get();

		List<IBaseResource> combinedResults = new ArrayList<>();
		Bundle searchResults = fhirClient.search().forResource(Coverage.class)
				.where(Coverage.BENEFICIARY.hasId(TransformerUtils.buildPatientId(beneficiary))).count(1)
				.returnBundle(Bundle.class).execute();

		searchResults.getEntry().forEach(e -> combinedResults.add(e.getResource()));

		Assert.assertNotNull(searchResults);
		Assert.assertEquals(1, searchResults.getEntry().size());
		Assert.assertEquals(MedicareSegment.values().length, searchResults.getTotal());

		/*
		 * Verify a link to the last page exists, but a link to the first page does not
		 * exist, since we are on the first page.
		 */
		Assert.assertNotNull(searchResults.getLink("last"));
		Assert.assertNull(searchResults.getLink("first"));

		while (searchResults.getLink(Bundle.LINK_NEXT) != null) {
			searchResults = fhirClient.loadPage().next(searchResults).execute();
			Assert.assertNotNull(searchResults);
			Assert.assertTrue(searchResults.hasEntry());

			/*
			 * Each page after the first should have a previous link.
			 */
			Assert.assertNotNull(searchResults.getLink(Bundle.LINK_PREV));

			searchResults.getEntry().forEach(e -> combinedResults.add(e.getResource()));
		}

		/*
		 * Verify that the combined results are the same size as there are
		 * MedicareSegments.
		 */
		Assert.assertEquals(MedicareSegment.values().length, combinedResults.size());

		/*
		 * Verify that each of the expected Coverages (one for every MedicareSegment) is
		 * present and looks correct.
		 */

		Coverage partACoverageFromSearchResult = (Coverage) combinedResults.stream().filter(e -> e instanceof Coverage)
				.map(e -> (Coverage) e)
				.filter(c -> TransformerConstants.COVERAGE_PLAN_PART_A.equals(c.getGrouping().getSubPlan())).findFirst()
				.get();
		CoverageTransformerTest.assertPartAMatches(beneficiary, partACoverageFromSearchResult);

		Coverage partBCoverageFromSearchResult = (Coverage) combinedResults.stream().filter(e -> e instanceof Coverage)
				.map(e -> (Coverage) e)
				.filter(c -> TransformerConstants.COVERAGE_PLAN_PART_B.equals(c.getGrouping().getSubPlan())).findFirst()
				.get();
		CoverageTransformerTest.assertPartBMatches(beneficiary, partBCoverageFromSearchResult);

		Coverage partDCoverageFromSearchResult = (Coverage) combinedResults.stream().filter(e -> e instanceof Coverage)
				.map(e -> (Coverage) e)
				.filter(c -> TransformerConstants.COVERAGE_PLAN_PART_D.equals(c.getGrouping().getSubPlan())).findFirst()
				.get();
		CoverageTransformerTest.assertPartDMatches(beneficiary, partDCoverageFromSearchResult);
	}

	/**
	 * Verifies that
	 * {@link CoverageResourceProvider#searchByBeneficiary(ca.uhn.fhir.rest.param.ReferenceParam)}
	 * works as expected for a {@link Beneficiary} that does exist in the DB, with a
	 * page size of 10 with fewer (3) results.
	 * 
	 * @throws FHIRException
	 *             (indicates test failure)
	 */
	@Test
	public void searchByExistingBeneficiaryWithLargePageSizesOnFewerResults() throws FHIRException {
		List<Object> loadedRecords = ServerTestUtils
				.loadData(Arrays.asList(StaticRifResourceGroup.SAMPLE_A.getResources()));
		IGenericClient fhirClient = ServerTestUtils.createFhirClient();

		Beneficiary beneficiary = loadedRecords.stream().filter(r -> r instanceof Beneficiary).map(r -> (Beneficiary) r)
				.findFirst().get();
		Bundle searchResults = fhirClient.search().forResource(Coverage.class)
				.where(Coverage.BENEFICIARY.hasId(TransformerUtils.buildPatientId(beneficiary))).count(10)
				.returnBundle(Bundle.class).execute();

		Assert.assertNotNull(searchResults);
		Assert.assertEquals(MedicareSegment.values().length, searchResults.getTotal());

		/*
		 * Verify that no paging links exist, since there should only be one page.
		 */
		Assert.assertNull(searchResults.getLink("first"));
		Assert.assertNull(searchResults.getLink(Bundle.LINK_NEXT));
		Assert.assertNull(searchResults.getLink(Bundle.LINK_PREV));
		Assert.assertNull(searchResults.getLink("last"));

		/*
		 * Verify that each of the expected Coverages (one for every MedicareSegment) is
		 * present and looks correct.
		 */

		Coverage partACoverageFromSearchResult = (Coverage) searchResults.getEntry().stream()
				.filter(e -> e.getResource() instanceof Coverage).map(e -> (Coverage) e.getResource())
				.filter(c -> TransformerConstants.COVERAGE_PLAN_PART_A.equals(c.getGrouping().getSubPlan())).findFirst()
				.get();
		CoverageTransformerTest.assertPartAMatches(beneficiary, partACoverageFromSearchResult);

		Coverage partBCoverageFromSearchResult = (Coverage) searchResults.getEntry().stream()
				.filter(e -> e.getResource() instanceof Coverage).map(e -> (Coverage) e.getResource())
				.filter(c -> TransformerConstants.COVERAGE_PLAN_PART_B.equals(c.getGrouping().getSubPlan())).findFirst()
				.get();
		CoverageTransformerTest.assertPartBMatches(beneficiary, partBCoverageFromSearchResult);

		Coverage partDCoverageFromSearchResult = (Coverage) searchResults.getEntry().stream()
				.filter(e -> e.getResource() instanceof Coverage).map(e -> (Coverage) e.getResource())
				.filter(c -> TransformerConstants.COVERAGE_PLAN_PART_D.equals(c.getGrouping().getSubPlan())).findFirst()
				.get();
		CoverageTransformerTest.assertPartDMatches(beneficiary, partDCoverageFromSearchResult);
	}

	/**
	 * Verifies that
	 * {@link CoverageResourceProvider#searchByBeneficiary(ca.uhn.fhir.rest.param.ReferenceParam)}
	 * works as expected for a {@link Beneficiary} that does not exist in the
	 * DB.
	 */
	@Test
	public void searchByMissingBeneficiary() {
		IGenericClient fhirClient = ServerTestUtils.createFhirClient();

		// No data is loaded, so this should return 0 matches.
		Bundle searchResults = fhirClient.search().forResource(Coverage.class)
				.where(Coverage.BENEFICIARY.hasId(TransformerUtils.buildPatientId("1234"))).returnBundle(Bundle.class)
				.execute();

		Assert.assertNotNull(searchResults);
		Assert.assertEquals(0, searchResults.getTotal());
	}

	/**
	 * Ensures that {@link ServerTestUtils#cleanDatabaseServer()} is called
	 * after each test case.
	 */
	@After
	public void cleanDatabaseServerAfterEachTestCase() {
		ServerTestUtils.cleanDatabaseServer();
	}
}
