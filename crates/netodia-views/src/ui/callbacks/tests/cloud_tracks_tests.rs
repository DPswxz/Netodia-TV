use super::patch_like;
use netodia_ui::TrackListRow;
use slint::{Model, ModelRc, VecModel};

fn row(id: &str) -> TrackListRow {
    TrackListRow { id: i32::MAX, remote_id: id.into(), ..TrackListRow::default() }
}

#[test]
fn liking_one_large_cloud_id_leaves_other_clamped_rows_unchanged() {
    let rows =
        ModelRc::new(VecModel::from(vec![row("2147483647"), row("2147483648"), row("3000000000")]));

    patch_like(&rows, 2_147_483_648, true);

    assert_eq!(rows.iter().map(|row| row.is_favorite).collect::<Vec<_>>(), [false, true, false]);
}

#[test]
fn unliking_an_unknown_id_does_not_modify_the_model() {
    let rows = ModelRc::new(VecModel::from(vec![
        TrackListRow { is_favorite: true, ..row("2147483648") },
        TrackListRow { is_favorite: true, ..row("3000000000") },
    ]));

    patch_like(&rows, 4_000_000_000, false);

    assert_eq!(rows.iter().map(|row| row.is_favorite).collect::<Vec<_>>(), [true, true]);
}
