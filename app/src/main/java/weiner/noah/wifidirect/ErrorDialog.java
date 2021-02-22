package weiner.noah.wifidirect;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import java.util.Objects;


public class ErrorDialog extends DialogFragment {
    public static final String ARG_MESSAGE = "message";
    /**
     * Shows an error message dialog.
     */
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        assert getArguments() != null;
        new AlertDialog.Builder(getActivity()).setMessage(getArguments().getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Objects.requireNonNull(getActivity()).finish();
                    }
                }).create();
        return super.onCreateDialog(savedInstanceState);
    }


    public static ErrorDialog newInstance(String message) {

        Bundle args = new Bundle();

        args.putString(ARG_MESSAGE, message);

        ErrorDialog fragment = new ErrorDialog();
        fragment.setArguments(args);
        return fragment;
    }

}
