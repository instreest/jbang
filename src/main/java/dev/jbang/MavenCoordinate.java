package dev.jbang;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Maven artifact coordinate in the form
 * <code>groupId:artifactId[:version[:classifier]][@type]</code>.
 */
public final class MavenCoordinate {
	private static final Pattern GAV = Pattern.compile(
			"^(?<groupid>[a-zA-Z0-9_.-]+):(?<artifactid>[a-zA-Z0-9_.-]+)(:(?<version>[^:@]*)(:(?<classifier>[^@]*))?)?(@(?<type>.*))?$");

	private final String groupId;
	private final String artifactId;
	private final String version;
	private final String classifier;
	private final String type;

	public MavenCoordinate(String groupId, String artifactId, String version, String classifier, String type) {
		this.groupId = groupId;
		this.artifactId = artifactId;
		this.version = version;
		this.classifier = classifier != null && classifier.isEmpty() ? null : classifier;
		this.type = type != null && !type.isEmpty() ? type : "jar";
	}

	public static boolean looksLikeACoordinate(String candidate) {
		return GAV.matcher(candidate).matches();
	}

	public static MavenCoordinate fromString(String coord) {
		Matcher m = GAV.matcher(coord);
		if (!m.matches()) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT, String.format(
					"Invalid dependency locator: '%s'. Expected format is groupId:artifactId:version[:classifier][@type]",
					coord));
		}
		return new MavenCoordinate(m.group("groupid"), m.group("artifactid"), formatVersion(m.group("version")),
				m.group("classifier"), m.group("type"));
	}

	/** Turns "1.2+" into the Maven open range "[1.2,)". */
	private static String formatVersion(String version) {
		if (version != null && version.endsWith("+")) {
			return "[" + version.substring(0, version.length() - 1) + ",)";
		}
		return version;
	}

	public String getGroupId() {
		return groupId;
	}

	public String getArtifactId() {
		return artifactId;
	}

	public String getVersion() {
		return version;
	}

	public String getClassifier() {
		return classifier;
	}

	public String getType() {
		return type;
	}

	public String toMavenString() {
		StringBuilder out = new StringBuilder(groupId).append(':').append(artifactId);
		if (version != null && !version.isEmpty()) {
			out.append(':').append(version);
		}
		if (classifier != null) {
			out.append(':').append(classifier);
		}
		if (!"jar".equals(type)) {
			out.append('@').append(type);
		}
		return out.toString();
	}

	@Override
	public String toString() {
		return toMavenString();
	}
}
