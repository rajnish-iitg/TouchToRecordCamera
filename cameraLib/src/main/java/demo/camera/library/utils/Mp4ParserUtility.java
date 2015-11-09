package demo.camera.library.utils;

import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.Container;
import com.coremedia.iso.boxes.SchemeTypeBox;
import com.coremedia.iso.boxes.TrackBox;
import com.googlecode.mp4parser.DataSource;
import com.googlecode.mp4parser.FileDataSourceImpl;
import com.googlecode.mp4parser.authoring.CencMp4TrackImplImpl;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Mp4TrackImpl;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.tracks.AppendTrack;
import com.googlecode.mp4parser.util.Path;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by root on 20/10/15.
 */
public class Mp4ParserUtility {

    public static void stitchVideos(String outFile, ArrayList<String> videoUris) throws IOException {
        List<Movie> inMovies = new ArrayList<>();
        for (String videoUri : videoUris) {
            inMovies.add(MovieCreator.build(videoUri));
        }

        List<Track> videoTracks = new LinkedList<>();
        List<Track> audioTracks = new LinkedList<>();

        for (Movie m : inMovies) {
            for (Track t : m.getTracks()) {
                if (t.getHandler().equals("soun")) {
                    audioTracks.add(t);
                }
                if (t.getHandler().equals("vide")) {
                    videoTracks.add(t);
                }
            }
        }

        Movie result = new Movie();

        if (audioTracks.size() > 0) {
            result.addTrack(new AppendTrack(audioTracks.toArray(new Track[audioTracks.size()])));
        }
        if (videoTracks.size() > 0) {
            result.addTrack(new AppendTrack(videoTracks.toArray(new Track[videoTracks.size()])));
        }

        Container out = new DefaultMp4Builder().build(result);

        FileChannel fc = new RandomAccessFile(String.format(outFile), "rw").getChannel();
        out.writeContainer(fc);
        fc.close();
    }

    public static class MovieCreator {
        public MovieCreator() {
        }

        public static Movie build(String file) throws IOException {
            return build((DataSource)(new FileDataSourceImpl(new File(file))));
        }


        public static Movie build(DataSource channel) throws IOException {
            IsoFile isoFile = new IsoFile(channel);
            Movie m = new Movie();

            List trackBoxes = isoFile.getMovieBox().getBoxes(TrackBox.class);
            Iterator var4 = trackBoxes.iterator();

            while(true) {
                while(var4.hasNext()) {
                    TrackBox trackBox = (TrackBox)var4.next();
                    SchemeTypeBox schm = (SchemeTypeBox) Path.getPath(trackBox, "mdia[0]/minf[0]/stbl[0]/stsd[0]/enc.[0]/sinf[0]/schm[0]");
                    if(schm != null && (schm.getSchemeType().equals("cenc") || schm.getSchemeType().equals("cbc1"))) {
                        m.addTrack(new CencMp4TrackImplImpl(channel.toString() + "[" + trackBox.getTrackHeaderBox().getTrackId() + "]", trackBox, new IsoFile[0]));
                    } else {
                        m.addTrack(new Mp4TrackImpl(channel.toString() + "[" + trackBox.getTrackHeaderBox().getTrackId() + "]", trackBox, new IsoFile[0]));
                    }
                }

                m.setMatrix(isoFile.getMovieBox().getMovieHeaderBox().getMatrix());
                return m;
            }

        }
    }

}
