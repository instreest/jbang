package dev.jbang;

/**
 * Thrown to terminate JBangLite with a specific exit status. A status of
 * {@link #EXIT_EXECUTE} (255) tells the launcher scripts that the text printed
 * on stdout is a command line they must execute.
 */
public class ExitException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public static final int EXIT_OK = 0;
	public static final int EXIT_GENERIC_ERROR = 1;
	public static final int EXIT_INVALID_INPUT = 2;
	public static final int EXIT_UNEXPECTED_STATE = 3;
	public static final int EXIT_INTERNAL_ERROR = 4;
	public static final int EXIT_EXECUTE = 255;

	private final int status;

	public ExitException(int status) {
		this.status = status;
	}

	public ExitException(int status, Throwable cause) {
		super(cause);
		this.status = status;
	}

	public ExitException(int status, String message) {
		this(status, message, null);
	}

	public ExitException(int status, String message, Throwable cause) {
		super(message, cause);
		this.status = status;
	}

	public int getStatus() {
		return status;
	}
}
