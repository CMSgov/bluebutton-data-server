package gov.hhs.cms.bluebutton.server.app.stu3.providers;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;

import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.dstu3.model.Coverage;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit;
import org.hl7.fhir.dstu3.model.IdType;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.Resource;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Component;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.RequiredParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import gov.hhs.cms.bluebutton.data.model.rif.Beneficiary;
import gov.hhs.cms.bluebutton.data.model.rif.Beneficiary_;

/**
 * This FHIR {@link IResourceProvider} adds support for STU3 {@link Coverage}
 * resources, derived from the CCW beneficiary enrollment data.
 */
@Component
public final class CoverageResourceProvider implements IResourceProvider {
	/**
	 * A {@link Pattern} that will match the {@link Coverage#getId()}s used in
	 * this application.
	 */
	private static final Pattern COVERAGE_ID_PATTERN = Pattern.compile("(.*)-(\\p{Alnum}+)");

	private EntityManager entityManager;
	private MetricRegistry metricRegistry;

	/**
	 * @param entityManager
	 *            a JPA {@link EntityManager} connected to the application's
	 *            database
	 */
	@PersistenceContext
	public void setEntityManager(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	/**
	 * @param metricRegistry
	 *            the {@link MetricRegistry} to use
	 */
	@Inject
	public void setMetricRegistry(MetricRegistry metricRegistry) {
		this.metricRegistry = metricRegistry;
	}

	/**
	 * @see ca.uhn.fhir.rest.server.IResourceProvider#getResourceType()
	 */
	@Override
	public Class<? extends IBaseResource> getResourceType() {
		return Coverage.class;
	}

	/**
	 * <p>
	 * Adds support for the FHIR "read" operation, for {@link Coverage}s. The
	 * {@link Read} annotation indicates that this method supports the read
	 * operation.
	 * </p>
	 * <p>
	 * Read operations take a single parameter annotated with {@link IdParam},
	 * and should return a single resource instance.
	 * </p>
	 * 
	 * @param coverageId
	 *            The read operation takes one parameter, which must be of type
	 *            {@link IdType} and must be annotated with the {@link IdParam}
	 *            annotation.
	 * @return Returns a resource matching the specified {@link IdDt}, or
	 *         <code>null</code> if none exists.
	 */
	@Read(version = false)
	public Coverage read(@IdParam IdType coverageId) {
		if (coverageId == null)
			throw new IllegalArgumentException();
		if (coverageId.getVersionIdPartAsLong() != null)
			throw new IllegalArgumentException();

		String coverageIdText = coverageId.getIdPart();
		if (coverageIdText == null || coverageIdText.trim().isEmpty())
			throw new IllegalArgumentException();

		Matcher coverageIdMatcher = COVERAGE_ID_PATTERN.matcher(coverageIdText);
		if (!coverageIdMatcher.matches())
			throw new ResourceNotFoundException(coverageId);
		String coverageIdSegmentText = coverageIdMatcher.group(1);
		Optional<MedicareSegment> coverageIdSegment = MedicareSegment.selectByUrlPrefix(coverageIdSegmentText);
		if (!coverageIdSegment.isPresent())
			throw new ResourceNotFoundException(coverageId);
		String coverageIdBeneficiaryIdText = coverageIdMatcher.group(2);

		Beneficiary beneficiaryEntity;
		try {
			beneficiaryEntity = findBeneficiaryById(coverageIdBeneficiaryIdText);
		} catch (NoResultException e) {
			throw new ResourceNotFoundException(
					new IdDt(Beneficiary.class.getSimpleName(), coverageIdBeneficiaryIdText));
		}

		Coverage coverage = CoverageTransformer.transform(metricRegistry, coverageIdSegment.get(), beneficiaryEntity);
		return coverage;
	}

	/**
	 * <p>
	 * Adds support for the FHIR "search" operation for {@link Coverage}s,
	 * allowing users to search by {@link Coverage#getBeneficiary()}.
	 * </p>
	 * <p>
	 * The {@link Search} annotation indicates that this method supports the
	 * search operation. There may be many different methods annotated with this
	 * {@link Search} annotation, to support many different search criteria.
	 * </p>
	 * 
	 * @param beneficiary
	 *            a {@link ReferenceParam} for the
	 *            {@link Coverage#getBeneficiary()} to try and find matches for
	 * @param startIndex
	 *            an {@link OptionalParam} for the startIndex (or offset) used to
	 *            determine pagination
	 * @param requestDetails
	 *            a {@link RequestDetails} containing the details of the request
	 *            URL, used to parse out pagination values
	 * @return Returns a {@link List} of {@link Coverage}s, which may contain
	 *         multiple matching resources, or may also be empty.
	 */
	@Search
	public Bundle searchByBeneficiary(@RequiredParam(name = Coverage.SP_BENEFICIARY) ReferenceParam beneficiary,
			@OptionalParam(name = "startIndex") String startIndex, RequestDetails requestDetails) {
		List<Coverage> coverages;
		try {
			Beneficiary beneficiaryEntity = findBeneficiaryById(beneficiary.getIdPart());
			coverages = CoverageTransformer.transform(metricRegistry, beneficiaryEntity);
		} catch (NoResultException e) {
			coverages = new LinkedList<>();
		}

		Bundle bundle = new Bundle();
		PagingArguments pagingArgs = new PagingArguments(requestDetails);
		if (pagingArgs.isPagingRequested()) {
			/*
			 * FIXME: Due to a bug in HAPI-FHIR described here
			 * https://github.com/jamesagnew/hapi-fhir/issues/1074 paging for count=0 is not
			 * working correctly.
			 */
			int numToReturn = Math.min(pagingArgs.getPageSize(), coverages.size());
			List<Coverage> resources = coverages.subList(pagingArgs.getStartIndex(),
					pagingArgs.getStartIndex() + numToReturn);
			bundle = addResourcesToBundle(bundle, resources);
			pagingArgs.addPagingLinks(bundle, "/Coverage?", "&beneficiary=", beneficiary.getIdPart(), coverages.size());
		} else {
			bundle = addResourcesToBundle(bundle, coverages);
		}

		bundle.setTotal(coverages.size());

		return bundle;
	}

	/**
	 * @param beneficiaryId
	 *            the {@link Beneficiary#getBeneficiaryId()} value to find a
	 *            matching {@link Beneficiary} for
	 * @return the {@link Beneficiary} that matches the specified
	 *         {@link Beneficiary#getBeneficiaryId()} value
	 * @throws NoResultException
	 *             A {@link NoResultException} will be thrown if no matching
	 *             {@link Beneficiary} can be found in the database.
	 */
	private Beneficiary findBeneficiaryById(String beneficiaryId)
			throws NoResultException {
		Timer.Context timerBeneQuery = metricRegistry
				.timer(MetricRegistry.name(getClass().getSimpleName(), "query", "bene_by_id")).time();
		try {
			CriteriaBuilder builder = entityManager.getCriteriaBuilder();
			CriteriaQuery<Beneficiary> criteria = builder.createQuery(Beneficiary.class);
			Root<Beneficiary> root = criteria.from(Beneficiary.class);
			criteria.select(root);
			criteria.where(builder.equal(root.get(Beneficiary_.beneficiaryId), beneficiaryId));

			Beneficiary beneficiaryEntity = entityManager.createQuery(criteria).getSingleResult();
			return beneficiaryEntity;
		} finally {
			timerBeneQuery.stop();
		}
	}

	/**
	 * @param bunlde
	 *            a {@link Bundle} to add the list of {@link ExplanationOfBenefit}
	 *            resources to.
	 * @param list
	 *            a list of {@link Patient}, of which a portion will be added to the
	 *            bundle based on the paging values
	 * @return Returns a {@link Bundle} of {@link Coverage}s, which may contain
	 *         multiple matching resources, or may also be empty.
	 */
	private Bundle addResourcesToBundle(Bundle bundle, List<Coverage> coverages) {
		for (IBaseResource res : coverages) {
			BundleEntryComponent entry = bundle.addEntry();
			entry.setResource((Resource) res);
		}

		return bundle;
	}
}
