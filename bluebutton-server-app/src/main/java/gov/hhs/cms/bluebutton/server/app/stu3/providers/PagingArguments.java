package gov.hhs.cms.bluebutton.server.app.stu3.providers;

import java.util.Optional;

import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Bundle.BundleLinkComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.justdavis.karl.misc.exceptions.BadCodeMonkeyException;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import gov.hhs.cms.bluebutton.data.model.rif.Beneficiary;

/*
 * PagingArguments encapsulates the arguments related to paging for the 
 * {@link ExplanationOfBenefit}, {@link Patient}, and {@link Coverage} requests.
 */
public final class PagingArguments {

	private static final Logger LOGGER = LoggerFactory.getLogger(ExplanationOfBenefitResourceProvider.class);

	private final Optional<Integer> pageSize;
	private final Optional<Integer> startIndex;
	private final String serverBase;

	public PagingArguments(RequestDetails requestDetails) {
		pageSize = parseIntegerParameters(requestDetails, "_count");
		startIndex = parseIntegerParameters(requestDetails, "startIndex");
		serverBase = requestDetails.getServerBaseForRequest();
	}

	/**
	 * @param requestDetails
	 *            the {@link RequestDetails} containing additional parameters for
	 *            the URL in need of parsing out
	 * @param parameterToParse
	 *            the parameter to parse from requestDetails
	 * @return Returns the parsed parameter as an Integer, null if the parameter is
	 *         not found.
	 */
	private Optional<Integer> parseIntegerParameters(RequestDetails requestDetails, String parameterToParse) {
		if (requestDetails.getParameters().containsKey(parameterToParse)) {
			try {
				return Optional.of(Integer.parseInt(requestDetails.getParameters().get(parameterToParse)[0]));
			} catch (NumberFormatException e) {
				LOGGER.warn("Invalid argument in request URL: " + parameterToParse + ". Cannot parse to Integer.", e);
				throw new InvalidRequestException(
						"Invalid argument in request URL: " + parameterToParse + ". Cannot parse to Integer.");
			}
		}
		return Optional.empty();
	}

	/*
	 * @return Returns true if the pageSize or startIndex is present (i.e. paging is
	 * requested), false if they are not present, and throws an
	 * IllegalArgumentException if the arguments are mismatched.
	 */
	public boolean isPagingRequested() {
		if (pageSize.isPresent())
			return true;
		else if (!pageSize.isPresent() && !startIndex.isPresent())
			return false;
		else
			// It's better to let clients requesting mismatched options know they goofed
			// than to try and guess their intent.
			throw new IllegalArgumentException(
					String.format("Mismatched paging arguments: pageSize='%s', startIndex='%s'", pageSize, startIndex));
	}

	/*
	 * @return Returns the pageSize as an integer. Note: the pageSize must exist at
	 * this point, otherwise paging would not have been requested.
	 */
	public int getPageSize() {
		if (!isPagingRequested())
			throw new BadCodeMonkeyException();
		return pageSize.get();
	}

	/*
	 * @return Returns the startIndex as an integer. If the startIndex is not set,
	 * return 0.
	 */
	public int getStartIndex() {
		if (!isPagingRequested())
			throw new BadCodeMonkeyException();
		if (startIndex.isPresent()) {
			return startIndex.get();
		}
		return 0;
	}

	/*
	 * @return Returns the serverBase.
	 */
	public String getServerBase() {
		return serverBase;
	}

	/**
	 * @param bundle
	 *            the {@link Bundle} to which links are being added
	 * @param resource
	 *            the {@link String} the resource being provided by the paging link
	 * @param searchByDesc
	 *            the {@link String} field the search is being performed on
	 * @param identifier
	 *            the {@link String} identifier being searched for
	 * @param numTotalResults
	 *            the number of total resources matching the
	 *            {@link Beneficiary#getBeneficiaryId()}
	 */
	public void addPagingLinks(Bundle bundle, String resource, String searchByDesc, String identifier,
			int numTotalResults) {

		Integer pageSize = getPageSize();
		Integer startIndex = getStartIndex();

		bundle.addLink(new BundleLinkComponent().setRelation("first")
				.setUrl(createPagingLink(resource, searchByDesc, identifier, 0, pageSize)));

		if (startIndex + pageSize < numTotalResults) {
			bundle.addLink(new BundleLinkComponent().setRelation(Bundle.LINK_NEXT)
					.setUrl(createPagingLink(resource, searchByDesc, identifier, startIndex + pageSize, pageSize)));
		}

		if (startIndex - pageSize >= 0) {
			bundle.addLink(new BundleLinkComponent().setRelation(Bundle.LINK_PREV)
					.setUrl(createPagingLink(resource, searchByDesc, identifier, startIndex - pageSize, pageSize)));
		}

		/*
		 * This formula rounds numTotalResults down to the nearest multiple of pageSize
		 * that's less than and not equal to numTotalResults
		 */
		int lastIndex;
		try {
			lastIndex = (numTotalResults - 1) / pageSize * pageSize;
		} catch (ArithmeticException e) {
			throw new InvalidRequestException(String.format("Cannot divide by zero: pageSize=%s", pageSize));
		}
		bundle.addLink(new BundleLinkComponent().setRelation("last")
				.setUrl(createPagingLink(resource, searchByDesc, identifier, lastIndex, pageSize)));
	}

	/**
	 * @return Returns the URL string for a paging link.
	 */
	private String createPagingLink(String resource, String descriptor, String id, int startIndex, int theCount) {
		StringBuilder b = new StringBuilder();
		b.append(serverBase + resource);
		b.append("_count=" + theCount);
		b.append("&startIndex=" + startIndex);
		b.append(descriptor + id);

		return b.toString();
	}
}
