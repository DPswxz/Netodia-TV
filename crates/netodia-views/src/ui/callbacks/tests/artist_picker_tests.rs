use super::unique_choices;

#[test]
fn artists_keep_credit_order_and_exact_large_ids() {
    let choices = vec![
        (4_000_000_000, "Guest".to_owned(), "guest.jpg".to_owned()),
        (2_147_483_648, "Lead".to_owned(), "lead.jpg".to_owned()),
        (4_000_000_000, "Repeated guest".to_owned(), "other.jpg".to_owned()),
    ];

    assert_eq!(
        unique_choices(choices),
        [
            (4_000_000_000, "Guest".to_owned(), "guest.jpg".to_owned()),
            (2_147_483_648, "Lead".to_owned(), "lead.jpg".to_owned()),
        ]
    );
}

#[test]
fn missing_artist_ids_do_not_become_navigation_targets() {
    let choices =
        vec![(0, "Missing".to_owned(), String::new()), (-1, "Invalid".to_owned(), String::new())];

    assert!(unique_choices(choices).is_empty());
}

#[test]
fn artists_with_the_same_name_and_different_ids_remain_separate() {
    let choices = vec![
        (42, "Same name".to_owned(), String::new()),
        (43, "Same name".to_owned(), String::new()),
    ];

    assert_eq!(unique_choices(choices.clone()), choices);
}
